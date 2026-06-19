# D&B Data Source Integration — Analysis, Findings & Implementation Plan

> Add `company_dnb_qualified` ("golden" D&B source) alongside `company_enrichment`. Run the **same
> LLM intent step**, search **both** tables, then **merge + re-rank** into one result set.
> This doc is implementation-ready: real-data findings, full schema/SQL/Java detail, a **phase-wise
> hybrid** design (hard SQL filter on both tables + vector search), and the **per-table sector
> mapping tables** that make the SQL actually return records.

**TL;DR recommendation:** Hybrid = `LLM intent → hard SQL filter on both tables (sector via per-table
mapping) → vector semantic re-rank → cross-source merge/dedupe/re-rank`. Vector store: **pgvector**
(recommended — same Postgres, no new infra) with **Pinecone** as a drop-in alternative (Spring AI
`VectorStore` abstraction makes it a dependency+config swap).

---

## 1. How search works today (baseline)

`GET /api/search/enhanced-stream` (`SearchController`) → `SearchPipelineService.runSeedListEnhancedStream`:

1. `EnrichmentFilterService.extract(query)` — cheap LLM (`geminiFlash`, temp 0) classifies the query
   into a **closed taxonomy** (`taxonomy/Taxonomy.java`: 22 sectors, sub_tags, 6 GCC countries, 6
   revenue bands, 8 employee bands). Output validated against the vocab → `EnrichmentFilter`.
2. `CompanyEnrichmentQueryService.query(filter, limit)` — native SQL candidate pool over
   `company_enrichment` matched on sector / sector_tags / sub_tags overlap
   (`CompanyEnrichmentRepository.queryCandidatePool`), bounded to `min(500, max(limit*5,100))`.
3. `CompanyScore.score(row, filter)` — pure Java weighted scoring (primary sector, sector_mix
   significant/minor, sub-tag overlap + soft signals: country/revenue/employee/listed) → 0–100.
4. Rank, limit, then SSE emit + upsert into `Company` (`CompanyService.upsertNonDestructive`).

Everything keys off the **controlled taxonomy vocabulary** — the integration constraint.

**Already on the classpath (important):** Spring AI **1.1.7**, with
`spring-ai-starter-model-vertex-ai-embedding` + `spring-ai-starter-model-vertex-ai-gemini` and the
embedding props already set in `application.properties`. So the embedding model is half-wired; no new
LLM vendor needed. `AiConfig` builds the chat clients; we add an `EmbeddingModel` consumer + a
`VectorStore`.

---

## 2. The two datasets (real CSV: `docs/company_*_rows.csv`)

| Dimension | `company_enrichment` (5,247 rows) | `company_dnb_qualified` (18,718 rows) |
|---|---|---|
| Sector | closed 22-sector taxonomy + `sub_tags` + weighted `sector_mix` | NAICS free text: `parent_industry` (21), `industry_name` (178 leaves), `primary_industries[]` (358), `industry_tags[]` (370) |
| Country | 6 GCC, canonical names | **same canonical names** + 1 stray Pakistan row |
| Revenue | `revenue_band` (dirty: ~15 non-canonical variants) + `revenue_estimate_usd` | raw `sales_usd` only; **no band** (`revenue_usd_thousands` mislabeled — values are millions) |
| Employees | `employee_band` + estimate | raw `employees_estimate` (446 null); no band |
| Listed | `is_listed` bool | `is_private` bool + `stock_exchange` |
| Free text for embeddings | `business_description` 99% (avg 754 chars) | `raw_context` 100% (avg 740), `profile_raw` 99% |

---

## 3. Compatibility findings (from real-data analysis)

**F1 — Tag vocabularies do NOT overlap. At all.** `industry_tags ∩ sub_tags = 0`,
`industry_tags ∩ sector_tags = 0` (270 vs 370 distinct). DNB = `snake_case` NAICS
(`depository_credit_intermediation`); enrichment = curated `kebab-case` (`retail-banking`).
**No string-level join exists.** → tags cannot be a shared match key; need a mapping table or vectors.

**F2 — `sector_mix` / `sub_tags` are enrichment-only.** DNB has neither → taxonomy-only scoring is
coarser for DNB (single sector signal).

**F3 — Country already aligns.** DNB `country` uses identical canonical strings to `Taxonomy.COUNTRIES`.
Action: filter GCC in SQL (drops 1 Pakistan row), ignore `country_iso2`.

**F4 — Revenue needs normalization on BOTH sides.** `CompanyScore` matches only the 6 canonical bands.
DNB has only raw `sales_usd`. Enrichment `revenue_band` is dirty (~15 non-canonical variants that
silently never match today). One `revenueBandFor(usd)` helper fixes both.

**F5 — Static NAICS→taxonomy mapping is feasible but lossy.** Map leaf `industry_name` (178), NOT
`parent_industry` (noisy). Coverage: top 40 leaves → 87% of rows; top 100 → 97.6%. **Junk-drawer:**
21.5% of rows (4,029) have leaf = *"Administrative and Support and Waste Management..."* — NAICS
catch-all (facilities/staffing/travel/security/cleaning) that maps to no single sector.

**F6 — `primary_industries[]` is rich (avg 3.3/row, 99% >1) but polluted.** Generic tags
(`office_administrative_services`, `other_investment_pools_and_funds`) dominate many rows. A static
mapper mis-sectors marquee golden companies: **TAQA** (power utility) → financial/real-estate;
**Aldar** (real-estate dev) → investment-pools; Abu Dhabi Ports → correct logistics + admin noise.
→ Decisive evidence **against mapper-only**.

**F7 — Sources are complementary.** Normalized-name overlap = only **199** (enrichment 5,236 vs DNB
18,500). Merge ≈ **union**; dedupe handles a small tail.

**F8 — Both sources have strong free text** → embeddings bridge exactly the gap that breaks
string/taxonomy matching.

---

## 4. Hybrid architecture — phase-wise

Principle: **structured signals → SQL `WHERE` (precise, cheap); fuzzy sector relevance → vectors
(robust to vocab mismatch).** The SQL phase narrows to a candidate pool; the vector phase re-ranks
semantically; then cross-source merge.

```
query
  │
  ├─[P1] LLM intent  ─────────────► EnrichmentFilter (sectors, subTags, countries, bands, listed, limit, rationale)
  │
  ├─[P2] Hard SQL filter (BOTH tables, in parallel)
  │        enrichment: sector match via taxonomy (native, exists) + country/band/listed
  │        dnb:        sector match via NAICS→taxonomy mapping table + country/band/listed
  │        → two bounded candidate pools (≤ pool size each)
  │
  ├─[P3] Structured score (existing CompanyScore for enrichment; mapped-row scorer for DNB)
  │
  ├─[P4] Vector semantic re-rank (cosine of query-embedding vs each candidate's stored embedding)
  │        finalScore = w_vec * cosine + w_struct * structuredScore
  │
  ├─[P5] Merge + dedupe (DNB wins) + re-rank by finalScore + limit
  │
  └─[P6] SSE emit + upsert (source-aware)
```

### Phase 1 — Intent (unchanged)
Reuse `EnrichmentFilterService.extract(query, briefContext)` verbatim. It already yields the closed-
vocab `EnrichmentFilter`. No change.

### Phase 2 — Hard SQL filter on BOTH tables (with per-table sector mapping)

The whole point of "hard SQL filter" is to **return records** from each table using the SAME
`EnrichmentFilter`. The two tables speak different sector languages, so each table needs its own
**sector mapping** to translate the filter's taxonomy sectors into a `WHERE` predicate that table can
satisfy.

**2a. Enrichment table — sectors are already taxonomy (identity mapping).**
Extend the existing `queryCandidatePool` to also push the *structured* predicates (country, derived
revenue band, listed) into SQL instead of only Java-scoring them, so the pool is pre-filtered:

```sql
-- company_enrichment (sector vocab == taxonomy; mapping is identity)
WHERE (
  primary_sector = ANY(:sectorAll)        -- taxonomy sectors (primaries + adjacents)
  OR sector_tags && :sectorAll
  OR sub_tags && :subTags
)
AND (:countries IS NULL OR country = ANY(:countries))
AND (:revBands  IS NULL OR revenue_band = ANY(:revBands))   -- after F4 normalization (see 2c)
AND (:isListed  IS NULL OR is_listed = :isListed)
ORDER BY confidence DESC
LIMIT :pool
```

**2b. DNB table — sectors are NAICS; need a NAICS→taxonomy mapping table to filter.**
We translate the filter's taxonomy sectors into the **set of DNB NAICS leaf codes** that belong to
those sectors (the *reverse* of leaf→sector), then match DNB rows whose `industry`/`industry_name`
(or `primary_industries[]`) intersect that set:

```sql
-- company_dnb_qualified (NAICS vocab; mapping table expands taxonomy sectors -> NAICS leaves)
WHERE country = ANY(:gcc)                                    -- F3: drop stray non-GCC row
AND (
  industry = ANY(:naicsLeavesForRequestedSectors)           -- primary leaf match
  OR primary_industries && :naicsLeavesForRequestedSectors  -- richer recall (F6), array overlap
)
AND (:revBands IS NULL OR revenueBandFor(sales_usd) = ANY(:revBands))  -- derived in Java or SQL CASE
AND (:isListed IS NULL OR (NOT is_private) = :isListed)
ORDER BY sales_usd DESC NULLS LAST                          -- golden proxy: bigger, better-known first
LIMIT :pool
```

**The two mapping tables (the heart of "map sector in each table"):**

| Mapping | Direction | Where it lives | Used for |
|---|---|---|---|
| **Enrichment sector map** | taxonomy → taxonomy (**identity**) | none needed | enrichment SQL uses `EnrichmentFilter.primarySectors` directly |
| **DNB NAICS map** | leaf `industry` (NAICS slug) → **one taxonomy sector** | `taxonomy/NaicsTaxonomyMap.java` (static `Map<String,String>`) + reverse `Map<String,List<String>>` (sector → leaves) | DNB SQL expands the filter's taxonomy sectors into `:naicsLeavesForRequestedSectors` |

`NaicsTaxonomyMap` is hand-authored once from the 178 leaves (top 100 → 97.6% coverage). It is used
**only as the SQL recall filter** (get candidates into the pool) — it is **not** trusted for final
ranking (F6 mis-sectoring). The vector phase (P4) corrects ranking. So mapper noise is tolerable here:
worst case a row enters the pool and the vector score demotes it. The junk-drawer leaf can be **left
unmapped** (those rows still reachable via `primary_industries[]` overlap and via vector recall in the
optional vector-recall mode, see §6).

> Why mapping is still needed even with vectors: SQL must reduce 18.7k DNB rows to a bounded pool
> cheaply *before* embedding comparison (we don't vector-scan the whole table per query). The mapping
> table + structured predicates = the cheap recall gate; vectors = the precise re-rank.

**2c. Band normalization (F4) — shared helper used by both tables.**
Add to `PipelineUtils`:
- `revenueBandFor(Long usd)` → one of `Taxonomy.REVENUE_BANDS` (cutoffs: <10M, 10–50M, 50–250M,
  250M–1B, 1–10B, >10B). DNB uses `sales_usd`; enrichment uses `revenue_estimate_usd`.
- `normalizeRevenueBand(String raw)` → snaps dirty enrichment band strings to canonical (fallback when
  `revenue_estimate_usd` is null).
- `employeeBandFor(Integer count)` → one of `Taxonomy.EMPLOYEE_BANDS` (1-10 … 10k+). DNB
  `employees_estimate`; 446 null → leave band null (scorer tolerates).

### Phase 3 — Structured score per source
- Enrichment: existing `CompanyScore.score(row.toScorable(), filter)` unchanged (has sector_mix/sub_tags).
- DNB: `DnbRow.toScorable()` builds a `ScorableRow` with `primarySector = NaicsTaxonomyMap.sectorFor(industry)`,
  `sectorTags = mapped sectors of primary_industries`, empty `sectorMix/subTags`, `country`,
  `revenueBand = revenueBandFor(sales_usd)`, `employeeBand`, `isListed = !is_private`. Reuses the same
  `CompanyScore` — no second scorer.

### Phase 4 — Vector semantic re-rank
- **Embedding text blob** (built once per company, stored): `name` + `(business_description | raw_context)` +
  `industry_name`/`primary_sector` + top tags. Both sources produce comparable blobs (F8).
- **Embedding model:** Vertex `text-embedding-005` (or `gemini-embedding-001`) via Spring AI
  `VertexAiTextEmbeddingModel` (starter already present). 768-dim (005) — cheap, ~24k one-time backfill.
- **Query embedding:** embed the user query (or the clean `filter.searchRationale()`) at search time.
- **Scoring:** `finalScore = w_vec * cosineSimilarity + w_struct * (structuredScore/100)`.
  Start `w_vec=0.6`, `w_struct=0.4`; tune via the eval harness (§9).
- Two execution modes (config flag):
  1. **Re-rank mode (recommended v1):** SQL gets the pool (P2), we embed-compare only the pool. No
     ANN index needed; pool is small. Works with pgvector OR Pinecone OR even in-memory cosine.
  2. **Vector-recall mode (v2):** also query the vector store by ANN for top-K semantically similar
     rows that the SQL filter missed (rescues junk-drawer / mis-mapped rows), union into the pool.

### Phase 5 — Merge + dedupe + re-rank (cross-source)
- Union both scored pools (mostly disjoint — F7).
- Dedupe by `normalize(name)` + website host (lowercase, strip `www.`); **DNB wins** on collision.
- Re-rank unified list by `finalScore` desc (confidence tiebreak), `.limit(filter.limit())`.
- Tag each surviving row `source ∈ {dnb, enrichment}` for display + rationale.

### Phase 6 — Emit + persist (mostly unchanged)
`SearchPipelineService` streams the same SSE events; `mapToCompany` becomes source-aware (reads
`DnbRow` or `EnrichedRow`); upsert via `CompanyService.upsertNonDestructive`.

---

## 5. Vector store options — pgvector vs Pinecone

Both are first-class Spring AI `VectorStore` implementations. Code against the `VectorStore` interface
so the choice is a **dependency + config swap**, decided by infra preference, not code.

| Factor | **pgvector** (recommended) | **Pinecone** |
|---|---|---|
| Infra | Postgres extension — **same DB we already run** (Supabase/Cloud SQL). No new service. | External managed SaaS; new account, API key, network egress. |
| Spring AI dep | `spring-ai-starter-vector-store-pgvector` | `spring-ai-starter-vector-store-pinecone` |
| Cost | Included in existing DB; storage only. | Per-pod / serverless usage billing. |
| Ops / secrets | None new (uses existing DB creds). | `PINECONE_API_KEY` in Secret Manager; index provisioning. |
| Scale | Fine for ~24k–1M vectors with HNSW/IVFFlat index. | Built for very large / high-QPS ANN; overkill here. |
| Transactionality | Same Postgres tx as the rest of the data; backfill is a plain SQL job. | Separate store; eventual consistency with the SQL rows. |
| Test profile | H2 has no pgvector → mock `VectorStore` / use `SimpleVectorStore` (in-memory) under `test`. | Mock the client under `test`. |
| Migration cost if we switch | — | Swap starter + `VectorStore` bean + config; embeddings re-pushed. |

**Recommendation: pgvector.** Dataset (~24k, growing slowly) sits comfortably in Postgres; keeps one
datastore, one set of creds, transactional backfill, no new vendor. Pinecone only earns its keep at
much larger scale or very high QPS — not our profile. **Keep Pinecone as a documented alternative**:
because we depend on `VectorStore`, adopting it later is a dependency+config change, not a rewrite.

**pgvector concrete bits:**
```sql
CREATE EXTENSION IF NOT EXISTS vector;
-- Option A: shared embeddings table (recommended — keeps source tables clean)
CREATE TABLE company_embeddings (
  source      text    NOT NULL,         -- 'enrichment' | 'dnb'
  source_id   bigint  NOT NULL,         -- FK to the row's id in its table
  embedding   vector(768) NOT NULL,
  text_blob   text,                     -- what was embedded (debug/reproducibility)
  updated_at  timestamptz DEFAULT now(),
  PRIMARY KEY (source, source_id)
);
CREATE INDEX ON company_embeddings USING hnsw (embedding vector_cosine_ops);
```
> `ddl-auto=none` — this DDL is applied externally like the rest of the schema (baseline at d0741b8).

---

## 6. Fallback / staging if vector infra slips
**v1 (no vectors):** ship P1→P3→P5 only — mapper-driven SQL recall + existing `CompanyScore` +
merge/dedupe/re-rank. Accept F5/F6 accuracy loss (junk-drawer skipped, some mis-sectoring). All
band/country/merge code (F3/F4/F7) is reused unchanged when vectors land. **v2:** add P4 (re-rank
mode), then vector-recall mode. Nothing thrown away.

---

## 7. Open decisions (pick before/with implementation)
1. **Vector store:** pgvector (recommended) vs Pinecone — §5.
2. **Embedding model & dim:** `text-embedding-005` (768) recommended.
3. **DNB sector mapping scope:** map top 100 NAICS leaves (97.6%); junk-drawer → leave unmapped + rely
   on `primary_industries[]` overlap + vector recall, or disambiguate via `primary_industries[]`.
4. **Mining/O&G leaf (1,021):** map to Oil & Gas-Upstream (simple) or split via `primary_industries[]`.
5. **Weights** `w_vec=0.6 / w_struct=0.4` start; tune on eval harness.
6. **v1-no-vector first, or vectors immediately** — §6.

---

## 8. Critical files
- **New:** `entity/CompanyDnbQualified.java`, `repository/CompanyDnbQualifiedRepository.java`,
  `taxonomy/NaicsTaxonomyMap.java` (leaf→sector + reverse sector→leaves),
  `service/pipeline/DnbRow.java`, `service/pipeline/CompanyDnbQueryService.java`,
  `service/pipeline/ResultMergeService.java`,
  `service/pipeline/EmbeddingService.java` (+ `VectorStore` bean in `AiConfig`),
  `service/pipeline/VectorRerankService.java`, `company_embeddings` schema.
- **Modify:** `service/pipeline/EnrichedCompanyMatch.java` (+`source`, +`finalScore`),
  `service/pipeline/SearchPipelineService.java` (orchestrate P2–P5, source-aware `mapToCompany`),
  `service/pipeline/CompanyScore.java` (expose structured score for hybrid blend),
  `service/pipeline/PipelineUtils.java` (`revenueBandFor`, `employeeBandFor`, `normalizeRevenueBand`,
  `normalizeName`), `repository/CompanyEnrichmentRepository.java` (push structured predicates),
  `config/AiConfig.java` (`EmbeddingModel` + `VectorStore`), `application.properties`
  (`app.search.dnb.enabled`, `app.search.vector.enabled`, `w_vec`/`w_struct`, embedding model,
  vector-store config), `pom.xml` (vector-store starter).
- **Reuse unchanged:** `EnrichmentFilterService`, `EnrichmentFilter`, `Taxonomy`,
  `CompanyService.upsertNonDestructive`, SSE/controller layer.

## 9. Verification
- **Unit:** `PipelineUtilsTest` band boundaries; `NaicsTaxonomyMapTest` (leaf→sector + reverse
  expansion); `CompanyDnbQueryServiceTest` (mocked repo → mapped, scored, sorted);
  `VectorRerankServiceTest` (mocked `VectorStore`/embedder → cosine blend order);
  `ResultMergeServiceTest` (dedupe prefers DNB, re-rank, limit).
- **Eval harness** (`src/test/java/com/globaltalenthub/eval/`, `PromptEvaluationIT` + `EvalReport`):
  add cases that a mapper fails — "power utilities in UAE" must surface **TAQA**; "real estate
  developers in Abu Dhabi" must surface **Aldar** — assert hybrid surfaces them, compare vs mapper-only.
- **E2E:** `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test`; then
  `mvn spring-boot:run` and hit `/api/search/enhanced-stream?query=...` — confirm golden DNB rows
  surface and outrank weaker enrichment rows; toggle `app.search.dnb.enabled` /
  `app.search.vector.enabled` to verify each stage independently.
- **Data:** confirm `company_dnb_qualified` + (pgvector) `vector` extension + `company_embeddings`
  exist in target DB (`ddl-auto=none`); run the one-time embedding backfill job.
