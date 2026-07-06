# DNB + Enrichment — SQL Static Search & Mapping (hybrid Phase 2)

> Scope: the **LLM intent step + SQL static-filter phase** of the hybrid (`LLM intent → hard
> SQL filter on both tables`). Vector re-rank, merge/dedupe and SSE wiring are deliberately
> **out of scope** here (covered in `dnb-integration-analysis.md`). This doc is the contract
> for: *what the LLM prompt produces*, *how that one `EnrichmentFilter` becomes a `WHERE`
> clause on each table*, and *what transforms/mappings make the same filter elements queryable
> against both vocabularies.*

DNB (`company_dnb_qualified`, 18,718 rows) is the **primary** source (golden, accurate).
Enrichment (`company_enrichment`, 5,247 rows) is the **secondary** source. Both are filtered
in parallel from the **same** `EnrichmentFilter` produced by **one** LLM intent step.

**Key design decision — the prompt does NOT emit NAICS.** DNB becoming primary does *not*
mean the LLM should classify into NAICS. The prompt stays in the **closed taxonomy vocabulary**
(stable, finite, injection-safe, human-curated). The taxonomy → NAICS translation happens
*after* the LLM, in code, via the mapping table (§4 Phase B). One prompt feeds both tables; DNB's
different vocabulary is a SQL-layer concern, not a prompt concern. What *does* change is the
prompt's **framing and emphasis** (§1) so the intent it extracts serves a DNB-primary query.

---

## 1. LLM intent prompt — changes for DNB-primary

### 1.1 What exists today (`EnrichmentFilterService.buildSystemPrompt`)

One cheap-LLM call (`geminiFlash`, temp 0) maps the query to a JSON `EnrichmentFilter`:

```json
{ "primarySectors": [], "subTags": [], "countries": [],
  "employeeBands": [], "revenueBands": [], "isListed": null,
  "searchRationale": "", "limit": null }
```

Every array is **validated against `Taxonomy`** server-side (`keepInSet`) — the model only
*selects* tokens from fixed lists; non-vocabulary tokens are dropped. The prompt is built once
at startup from `Taxonomy.SUB_TAGS_BY_SECTOR` (sectors + sub_tags + bands + countries).

### 1.2 Why the output shape is already correct (and what isn't)

The output fields are exactly what **both** SQL filters need (§4) — no new fields required.
`primarySectors`/`subTags`/`countries`/`isListed`/`limit` all map cleanly. The problem is
**not** the shape; it's two framing issues that hurt DNB-primary recall:

| Issue | Today | Effect on DNB | Fix |
|---|---|---|---|
| **Enrichment-centric framing** | prompt says "classify into the company_enrichment vocabulary" | reads as if only enrichment is searched; biases the model toward enrichment's narrow curated tags | reframe as source-agnostic *business intent* (§1.3) |
| **Bands de-emphasized** | "only if the query implies size/revenue" + bands were never pushed to SQL (Java-score only) | DNB bands precomputed + reliability-gated (§3.1) become a real discriminator; under-extraction wastes that | keep the rule but make banding count (handled in §4 Phases A/E, scorer side; prompt rule unchanged) |
| **No primary-source signal** | `searchRationale` is enrichment-flavored | minor | rationale wording neutral (§1.3) |

> Note: `revenueBands`/`employeeBands` were *already extracted* by the prompt but historically
> only fed the Java scorer, never SQL. That stays — bands remain soft (score-only), so a
> golden DNB row with a null/edge band is never excluded. The prompt rule for bands does **not**
> need to change; the value comes from §4 Phase A deriving real (reliability-flagged) bands on the DNB side so the
> already-extracted band filter finally has something accurate to match against.

### 1.3 Prompt changes (surgical — wording only, same JSON contract)

Only the **framing sentences** change. The vocabulary block, JSON schema, validation, and
injection guards stay byte-for-byte. Rename is conceptual, not in code (the record stays
`EnrichmentFilter`).

**(a) Opening line** — make it source-agnostic:

```
- You classify a business research query into a fixed, controlled vocabulary so a database can be filtered.
+ You classify a business research query into a fixed, controlled business vocabulary. The
+ extracted intent is used to search two company databases (a primary firmographic source and a
+ secondary enrichment source); your job is only to capture the query's business intent in the
+ controlled vocabulary below — never to name a data source, table, or industry code.
```

**(b) primarySectors rule** — nudge toward the broader/primary sector when the query is generic,
because DNB sectors are coarser (NAICS leaf, no `sector_mix`/`sub_tags` — F2). Keep sub_tags
for the niche:

```
- primarySectors: the sector(s) the query is about (usually 1-2).
+ primarySectors: the broad sector(s) the query is about (usually 1-2). Always include the
+ broad sector even when the query is niche — the niche goes in subTags. (A coarse primary
+ sector is what the primary firmographic source matches on.)
```

**(c) searchRationale** — neutral wording, no "enrichment":

```
- searchRationale: one sentence, plain English, describing what a valid result looks like.
+ searchRationale: one sentence, plain English, describing the ideal target company — used as
+ the semantic query for both sources.
```

Everything else (`countries`, `employeeBands`, `revenueBands`, `isListed`, `limit`, the
untrusted-DATA markers, the return-only-JSON instruction) is **unchanged**.

### 1.4 End-to-end: query → prompt → filter → two SQL queries

```
user query: "top 30 listed banks in Saudi Arabia with over $1B revenue"
        │
        ▼  EnrichmentFilterService.extract  (cheap LLM, taxonomy vocab, validated)
EnrichmentFilter {
  primarySectors: ["Banking & Financial Services"]
  adjacentSectors: ["Capital Markets & Asset Management","Insurance","Technology & Software"]  // derived
  subTags: []                       countries: ["Saudi Arabia"]
  revenueBands: ["$1-10B",">$10B"]  employeeBands: []
  isListed: true                    limit: 30
}
        │
        ├──────────────────────────────┬──────────────────────────────────────────────
        ▼ ENRICHMENT (identity)         ▼ DNB (mapping)
 sectorAll = primary+adjacent     sectorAll → NaicsTaxonomyMap.SECTOR_TO_LEAVES
 → WHERE primary_sector/          → :naicsLeaves = ["depository_credit_intermediation", ...]
   sector_tags/sub_tags match     → WHERE country = ANY(6 GCC)
 → country bias, confidence sort     AND (sector_primary = ANY(:sectorAll)   -- Phase A precomputed
 → Java score (clean bands)               OR primary_industries && :naicsLeaves)
                                     → country bias, sales_usd DESC sort
                                     → Java score: revenue_band ∈ {$1-10B,>$10B} IF reliable,
                                       is_listed (precomputed)   (Phase A/E)
```

Same filter, two `WHERE` clauses. The LLM never sees NAICS, never names a table; the mapping
table is the only place taxonomy meets NAICS.

---

---

## 2. Why mapping is needed (the core problem)

The LLM intent step (`EnrichmentFilterService.extract`) emits a single `EnrichmentFilter`
in **closed taxonomy vocabulary** (`taxonomy/Taxonomy.java`):

```
primarySectors[]  adjacentSectors[]  subTags[]  countries[]
revenueBands[]    employeeBands[]    isListed   limit
```

Enrichment **already speaks this vocabulary** — its `primary_sector`/`sector_tags`/`sub_tags`
ARE taxonomy strings, so its SQL filter is an *identity* mapping (the existing
`CompanyEnrichmentRepository.queryCandidatePool`).

DNB does **not**. It speaks NAICS:

| Filter element | Enrichment column (taxonomy) | DNB column (needs transform) |
|---|---|---|
| sector | `primary_sector`, `sector_tags[]` | `industry` (NAICS slug), `primary_industries[]` |
| subTags | `sub_tags[]` | — (no equivalent; **F1** zero overlap) |
| country | `country` (canonical) | `country` (**same canonical** — F3, no map) |
| revenueBands | `revenue_band` (dirty, 20 variants) | `sales_usd` (raw USD, no band) |
| employeeBands | `employee_band` (dirty, 25 variants) | `employees_estimate` (raw int, no band) |
| isListed | `is_listed` (bool) | `is_private` (bool — **invert**) |

So three transforms make the same filter queryable on DNB:
**(A) sector → NAICS leaf set** (mapping table), **(B) revenue/employee → bands** (derive),
**(C) `is_private` → `is_listed`** (boolean invert). Country needs nothing.

---

## 3. Verified data facts (re-checked against the real CSVs)

| Fact | Number | Consequence |
|---|---|---|
| DNB rows | 18,718 | reduce to a bounded pool cheaply, never full-scan |
| DNB distinct `industry` leaves | **178** | mapping table hand-authored once, finite |
| DNB distinct `parent_industry` | 21 | too coarse/noisy — map the **leaf**, not the parent (F5) |
| DNB countries | SA 6402, QA 5525, KW 2494, BH 2319, OM 1852, **UAE 125**, Pakistan 1 | filter to 6 GCC in SQL; drops the 1 stray (F3) |
| Top-40 leaf coverage | **87.2%** | mapping 40 leaves already covers most rows |
| Top-100 leaf coverage | **97.6%** | map ~100 leaves → near-total recall |
| Junk-drawer leaf (`administrative_and_support_and_waste_management…`) | **4,029 = 21.5%** | NAICS catch-all → **leave unmapped**, reach via `primary_industries[]` |
| Enrichment `revenue_band` variants | **20** (6 canonical + 14 dirty + 45 blank) | normalize (F4) |
| Enrichment `employee_band` variants | **25** (8 canonical + 17 dirty + 82 blank) | normalize (F4) |

### 3.1 ⚠ Revenue/employee data quality — the reason bands need a reliability flag

`sales_usd` is **`sales_revenue` × 1,000,000** (verified: ADCB `sales_revenue`=8498.3 →
`sales_usd`=8,498,295,740 = $8.5B). The unit is fine — **but most values are imputed, not real:**

| Signal | Finding | Risk if banded blindly |
|---|---|---|
| `sales_usd` placeholder cluster | **79.6%** (14,895 rows) share ~11 repeated values (8,088,500 ×6606; 10,143,650 ×2756; 16,229,800 ×1533 …) | Kempinski / Hilton / large hotels all tagged **"<$10M"** — user gets wrong revenue |
| `sales_usd` genuinely distinct | only **20.0%** (3,744 rows) | only these can be trusted for a precise band |
| `employees_reliability` | **86.5% "Modelled"** (16,184), 8.1% "Estimated", **only 3.1% "Actual"** (575) | modelled headcount → wrong employee band |
| `employees_estimate` placeholder cluster | emp=70 ×6553, emp=50 ×3981, emp=100 ×1856 … | same imputation pattern as revenue |
| `is_private` | 97% true; **473 false** exactly match the 473 with `stock_exchange` | `is_listed = NOT is_private` IS reliable — keep as a hard signal |

**Naive `revenueBandFor(sales_usd)` distribution** (proves the skew): `<$10M` 9,584 / `$10-50M`
8,144 / `$50-250M` 476 / `$250M-1B` 231 / `$1-10B` 156 / `>$10B` 48 — i.e. 95% land in the two
lowest bands purely from placeholders.

**Decision:** band DNB **offline** into new columns, but **stamp each band with a reliability
tier** (`actual` | `modelled` | `unknown`) derived from the placeholder set + `employees_reliability`.
The SQL/scorer treats a band as a *match-eligible* soft signal **only when reliable**; a modelled
band is stored (for display/debug) but does **not** drive filtering — so users never get a wrong
revenue/employee filter result. This is the concrete answer to "use most columns so users don't
get wrong data."

---

## 4. Implementation — phase by phase

Five phases. **Phase A** (offline DNB column transform) and **Phase B** (mapping table) are
**data-prep, done once outside the request path** — exactly what you green-lit. **Phases C–E**
are the request-time code that consumes them. Each phase has a verify step; do them in order.

```
A. DNB offline transform   → new columns: revenue_band, revenue_band_reliable,
   (one-time SQL job)         employee_band, employee_band_reliable, is_listed, sector_primary
B. Mapping table           → NaicsTaxonomyMap.java (leaf→sector + reverse) drives sector_primary in A
C. DNB query service       → CompanyDnbQualifiedRepository + CompanyDnbQueryService (consumes A)
D. Enrichment band cleanup → normalize dirty enrichment bands so the existing scorer matches (F4)
E. Scorer reuse            → DnbRow.toScorable() → existing CompanyScore (no second scorer)
```

---

### Phase A — DNB offline column transform (one-time SQL, outside the process)

Goal: precompute everything the query needs so the request path does **zero** per-row
transformation. `ddl-auto=none`, so the `ALTER`/`UPDATE` run as an external migration job
(same as the rest of the schema). Six new columns on `company_dnb_qualified`:

| New column | Type | Derived from | Rule |
|---|---|---|---|
| `revenue_band` | text | `sales_usd` | `revenueBandFor(sales_usd)` (same 6 cutoffs as `Taxonomy.REVENUE_BANDS`) |
| `revenue_band_reliable` | bool | `sales_usd` | `false` if `sales_usd` ∈ placeholder set (§3.1), else `true` |
| `employee_band` | text | `employees_estimate` | `employeeBandFor(employees_estimate)`; null if estimate null |
| `employee_band_reliable` | bool | `employees_reliability` | `true` only when `= 'Actual'` (3.1%); else `false` |
| `is_listed` | bool | `is_private` | `NOT is_private`; null when `is_private` null (79 rows) |
| `sector_primary` | text | `industry` (+ Phase B map) | `LEAF_TO_SECTOR[industry]`; null if leaf unmapped (junk drawer) |

> **Why `*_reliable` columns (the "don't show users wrong data" guard):** 79.6% of `sales_usd`
> and 96.9% of headcount are imputed (§3.1). We still store the band (useful for sort/display),
> but the query treats a band as **filter-eligible only when its `*_reliable` flag is true** —
> so "companies with >$1B revenue" never returns a Kempinski-tagged-`<$10M` placeholder as a
> *revenue match*. It can still surface via sector/country; it just doesn't get a false revenue hit.

Migration sketch (run once; idempotent via `IF NOT EXISTS`):

```sql
ALTER TABLE company_dnb_qualified
  ADD COLUMN IF NOT EXISTS revenue_band            text,
  ADD COLUMN IF NOT EXISTS revenue_band_reliable   boolean,
  ADD COLUMN IF NOT EXISTS employee_band           text,
  ADD COLUMN IF NOT EXISTS employee_band_reliable  boolean,
  ADD COLUMN IF NOT EXISTS is_listed               boolean,
  ADD COLUMN IF NOT EXISTS sector_primary          text;

-- revenue band + reliability
UPDATE company_dnb_qualified SET
  revenue_band = CASE
    WHEN sales_usd IS NULL          THEN NULL
    WHEN sales_usd <  10000000      THEN '<$10M'
    WHEN sales_usd <  50000000      THEN '$10-50M'
    WHEN sales_usd < 250000000      THEN '$50-250M'
    WHEN sales_usd < 1000000000     THEN '$250M-1B'
    WHEN sales_usd < 10000000000    THEN '$1-10B'
    ELSE '>$10B' END,
  revenue_band_reliable = (sales_usd IS NOT NULL AND sales_usd NOT IN (
    8088500,10143650,16229800,11766634,10047550,8052000,8436660,8081050,9040620,9645600,10052835
    /* + remaining placeholder values from the §3.1 audit — full list generated by the audit script */
  ));

-- employee band + reliability
UPDATE company_dnb_qualified SET
  employee_band = CASE
    WHEN employees_estimate IS NULL THEN NULL
    WHEN employees_estimate <=    10 THEN '1-10'
    WHEN employees_estimate <=    50 THEN '11-50'
    WHEN employees_estimate <=   200 THEN '51-200'
    WHEN employees_estimate <=   500 THEN '201-500'
    WHEN employees_estimate <=  1000 THEN '501-1k'
    WHEN employees_estimate <=  5000 THEN '1k-5k'
    WHEN employees_estimate <= 10000 THEN '5k-10k'
    ELSE '10k+' END,
  employee_band_reliable = (employees_reliability = 'Actual'),
  is_listed = CASE WHEN is_private IS NULL THEN NULL ELSE NOT is_private END;

-- sector_primary is set by the Phase B mapper (see B). Indexes for the recall gate:
CREATE INDEX IF NOT EXISTS idx_dnb_country        ON company_dnb_qualified (country);
CREATE INDEX IF NOT EXISTS idx_dnb_sector_primary ON company_dnb_qualified (sector_primary);
CREATE INDEX IF NOT EXISTS idx_dnb_primary_inds   ON company_dnb_qualified USING gin (primary_industries);
```

**Verify A:** `SELECT revenue_band, count(*) FILTER (WHERE revenue_band_reliable) AS reliable,
count(*) AS total FROM company_dnb_qualified GROUP BY 1` — confirm only ~20% reliable;
spot-check a known big company (ADCB) lands `$1-10B` reliable, a placeholder hotel lands
`<$10M` **not** reliable.

---

### Phase B — Sector mapping table (`taxonomy/NaicsTaxonomyMap.java`, NEW)

The single mapper that translates DNB's NAICS vocab ↔ the taxonomy. Used twice: (1) offline in
Phase A to populate `sector_primary`; (2) at query time to expand the filter's sectors into the
NAICS leaf set for `primary_industries[]` overlap recall.

```java
Map<String,String>        LEAF_TO_SECTOR;    // "depository_credit_intermediation" → "Banking & Financial Services"
Map<String,List<String>>  SECTOR_TO_LEAVES;  // reverse, derived once in a static block
```

- Author `LEAF_TO_SECTOR` for the **top ~100 leaves** (97.6% coverage). Junk-drawer leaf → **no
  entry** (those rows reach the pool only via `primary_industries[]` overlap + later vectors).
- **Recall gate only** — not trusted for ranking (F6: `primary_industries[]` mis-sectors
  TAQA/Aldar). A wrong row entering the pool is harmless; downstream score/vector demotes it.

Use the **real leaf distribution** so the mapping covers the rows users actually hit. Top leaves
(cumulative coverage) → sector, grounded in the CSV:

| Rows | Cum% | NAICS leaf | → Taxonomy sector |
|---|---|---|---|
| 4029 | 21.5% | `administrative_and_support_and_waste_management_and_remediation_services` | **(unmapped — junk drawer)** |
| 2540 | 35.1% | `construction` | Construction & Engineering |
| 1186 | 41.4% | `highway_street_and_bridge_construction` | Construction & Engineering |
| 1163 | 47.6% | `professional_scientific_and_technical_services` | Professional Services |
| 1021 | 53.1% | `mining_quarrying_and_oil_and_gas_extraction` | Oil & Gas - Upstream |
| 461 | 55.6% | `business_support_services` | Professional Services |
| 405 | 57.7% | `architectural_engineering_and_related_services` | Construction & Engineering |
| 341 | 59.5% | `depository_credit_intermediation` | Banking & Financial Services |
| 318 | 61.2% | `specialized_design_services` | Professional Services |
| 309 | 62.9% | `manufacturing` | Manufacturing & Industrial |
| 290 | 64.4% | `other_professional_scientific_and_technical_services` | Professional Services |
| 260 | 65.8% | `accommodation_and_food_services` | Hospitality, Travel & Tourism |
| 253 | 67.2% | `travel_arrangement_and_reservation_services` | Hospitality, Travel & Tourism |
| 238 | 68.5% | `lumber_and_other_construction_materials_merchant_wholesalers` | Construction & Engineering |
| 212 | 69.6% | `iron_and_steel_mills_and_ferroalloy_manufacturing` | Manufacturing & Industrial |
| … | →97.6% | (author the remaining ~85 leaves the same way) | … |

> Open decisions to settle while authoring: junk-drawer leaf stays unmapped (rely on
> `primary_industries[]`); mining/O&G leaf (1,021) → *Oil & Gas - Upstream* (refine via
> `primary_industries[]` later if precision demands). `business_support_services` /
> `specialized_design_services` are arguably Professional Services vs sector-specific — pick
> the dominant reading; mapper noise is corrected downstream.

Phase A's `sector_primary` UPDATE consumes this map (run the mapper as a small data job, or emit
a `leaf → sector` SQL `VALUES` table generated from `LEAF_TO_SECTOR` and join):

```sql
-- generated from LEAF_TO_SECTOR (one row per mapped leaf)
WITH m(leaf, sector) AS (VALUES
  ('depository_credit_intermediation','Banking & Financial Services'),
  ('construction','Construction & Engineering'), ... )
UPDATE company_dnb_qualified d SET sector_primary = m.sector
FROM m WHERE d.industry = m.leaf;
```

**Verify B:** `NaicsTaxonomyMapTest` — every `LEAF_TO_SECTOR` value ∈ `Taxonomy.SECTORS`;
`SECTOR_TO_LEAVES` is the exact inverse; junk-drawer leaf absent; empty sector → empty list (no NPE).
After Phase A runs: `SELECT count(*) FROM company_dnb_qualified WHERE sector_primary IS NULL`
≈ junk-drawer + long tail (~2.4% + 21.5%).

---

### Phase C — DNB query service (request path; consumes A + B)

Country needs **no** mapping — DNB `country` already uses the canonical `Taxonomy.COUNTRIES`
strings (F3); filtering to the 6 GCC drops the stray Pakistan row.

Soft signals (band/listed) follow the existing enrichment convention: they **bias pool order +
feed the scorer**, never hard-exclude (a null/unreliable band must not drop a golden row).
Country is pushed into pool ordering so a thin requested country is not crowded out — mirrors
`CompanyEnrichmentRepository:45`. Pool size reuses `min(500, max(limit*5, 100))`
(`CompanyEnrichmentQueryService:43`).

**Query-time leaf expansion** (reuse of Phase B reverse map):

```java
List<String> sectorAll = new ArrayList<>(filter.primarySectors());
sectorAll.addAll(filter.adjacentSectors());
List<String> naicsLeaves = sectorAll.stream()
    .flatMap(s -> NaicsTaxonomyMap.SECTOR_TO_LEAVES.getOrDefault(s, List.of()).stream())
    .distinct().toList();                 // → :naicsLeaves (Postgres array literal via toArrayLiteral)
```

**`CompanyDnbQualifiedRepository.queryCandidatePool`** (NEW) — sector recall via the precomputed
`sector_primary` (fast, indexed) OR `primary_industries[]` overlap (richer recall, F6):

```sql
SELECT id, duns, name, country, industry, sector_primary, primary_industries,
       revenue_band, revenue_band_reliable, employee_band, employee_band_reliable,
       is_listed, sales_usd, website, raw_context
FROM company_dnb_qualified
WHERE country = ANY(:gcc)                                        -- F3: 6 GCC, drops stray
  AND (
    (:hasSector AND sector_primary = ANY(:sectorAll))            -- precomputed sector (Phase A)
    OR (:hasSector AND primary_industries && :naicsLeaves)       -- array overlap recall (F6)
  )
ORDER BY (country = ANY(:countries)) DESC, sales_usd DESC NULLS LAST  -- golden proxy: bigger first
LIMIT :pool
```

- `:sectorAll` = the filter's taxonomy sectors (primaries + adjacents) — matched directly against
  the precomputed `sector_primary` (no per-query mapping for the primary path).
- `:naicsLeaves` = Phase B expansion — only for the `primary_industries[]` overlap path.
- Bands are **not** in this `WHERE` — they're scored in Java (Phase E) and only when reliable.

**`CompanyDnbQueryService`** mirrors `CompanyEnrichmentQueryService`: repo → map rows → score →
sort by `matchScore` (tiebreak `sales_usd` desc — the golden proxy, vs `confidence` for
enrichment) → `limit`. Reuse `CompanyEnrichmentQueryService.toArrayLiteral` for array params.

**Verify C:** `CompanyDnbQueryServiceTest` (mocked repo) — "banking in Saudi Arabia" expands to
`depository_credit_intermediation`, returns SA banks; pool bounded; sorted by score then
`sales_usd`; junk-drawer row surfaces only via `primary_industries[]`.

---

### Phase D — Enrichment band cleanup (F4, scorer parity)

Enrichment `revenue_band` (20 variants) / `employee_band` (25 variants) are dirty — 14/17 dirty
forms silently never match the scorer today. Two options; pick one:

- **D1 (preferred, symmetric with A):** offline-normalize enrichment too — add canonical
  `revenue_band` / `employee_band` (or overwrite) via the same cutoffs, preferring
  `revenue_estimate_usd` when present, else snapping the dirty string. One SQL job, no request-path cost.
- **D2 (code-only):** add `PipelineUtils.normalizeRevenueBand(raw)` and band-from-estimate
  helpers, applied in `CompanyEnrichmentQueryService` row mapping before scoring.

Either way the scorer is unchanged. Enrichment bands are **trusted** (curated estimates), so no
`*_reliable` flag needed on that side — only DNB needs it (§3.1).

**Verify D:** `PipelineUtilsTest` boundary values + each of the 14 dirty revenue / 17 dirty
employee variants snaps to a canonical `Taxonomy` band; null/blank → null.

---

### Phase E — Scorer reuse (no second scorer)

`CompanyScore.score(ScorableRow, EnrichmentFilter)` is source-agnostic. A DNB row maps to the
same `ScorableRow`, reading the **precomputed** columns from Phase A — and passing a band only
when its reliability flag is true, so unreliable bands never produce a false band match:

```java
// DnbRow.toScorable()  (DnbRow = NEW projection record, mirrors EnrichedRow)
new CompanyScore.ScorableRow(
    sectorPrimary,                                                    // Phase A precomputed
    NaicsTaxonomyMap.mappedSectorsOf(primaryIndustries),             // sectorTags (mapped, distinct)
    List.of(),                                                       // sectorMix — DNB has none (F2)
    List.of(),                                                       // subTags   — DNB has none (F1)
    country,
    revenueBandReliable ? revenueBand : null,                        // reliable-only (§3.1 guard)
    employeeBandReliable ? employeeBand : null,                      // reliable-only
    isListed);                                                       // Phase A precomputed
```

DNB's empty `sectorMix`/`subTags` means its sector signal is coarser than enrichment (F2) — as
expected for the SQL phase; the vector phase (out of scope) restores fine-grained relevance.

**Verify E:** in `CompanyDnbQueryServiceTest`, assert a placeholder-revenue row does **not** get
a revenue-band match even when the query requests that band; a reliable row does.

---

## 7. Files

**Data prep (one-time, outside the process — Phases A & B)**
- `db/migration/dnb_band_sector_columns.sql` (or your external migration location) — `ALTER`
  adds `revenue_band`, `revenue_band_reliable`, `employee_band`, `employee_band_reliable`,
  `is_listed`, `sector_primary` + the `UPDATE`s + indexes (§4 Phase A). Run once; `ddl-auto=none`.
- Full placeholder-value list for `revenue_band_reliable` — generated by the §3.1 audit script.

**New code**
- `taxonomy/NaicsTaxonomyMap.java` — `LEAF_TO_SECTOR` (~100 leaves) + derived `SECTOR_TO_LEAVES`
  + `mappedSectorsOf(primary_industries)` helper (Phase B; also emits the `VALUES` table for A).
- `entity/CompanyDnbQualified.java` — JPA entity incl. the 6 new columns (`ddl-auto=none`).
- `repository/CompanyDnbQualifiedRepository.java` — `queryCandidatePool` (Phase C).
- `service/pipeline/DnbRow.java` — projection record + reliability-aware `toScorable()` (Phase E).
- `service/pipeline/CompanyDnbQueryService.java` — mirror of `CompanyEnrichmentQueryService` (Phase C).

**Modify**
- `service/pipeline/EnrichmentFilterService.java` — **prompt framing only** (§1.3): source-agnostic
  opening line, broader-sector nudge on `primarySectors`, neutral `searchRationale` wording. JSON
  schema, vocabulary block, `keepInSet` validation and injection guards **unchanged**.
- `service/pipeline/PipelineUtils.java` — add band helpers (`revenueBandFor`, `employeeBandFor`,
  `normalizeRevenueBand`) so Java + the migration share one cutoff definition (Phase D).
- `service/pipeline/CompanyEnrichmentQueryService.java` — normalized enrichment bands to the scorer
  (Phase D, if D2); candidate-pool SQL unchanged.
- `application.properties` — `app.search.dnb.enabled` flag (toggle DNB source independently).

**Reuse unchanged**
- `EnrichmentFilter` (record + fields), `Taxonomy`, `CompanyScore`,
  `CompanyEnrichmentQueryService.toArrayLiteral`, the pool-size rule. (LLM output shape correct
  as-is — only prompt *wording* changes.)

---

## 8. Verification (per phase)

| Phase | Check |
|---|---|
| **A** | `GROUP BY revenue_band` shows ~20% `revenue_band_reliable`; ADCB → `$1-10B` reliable; a placeholder hotel → `<$10M` **not** reliable; `is_listed` true count = 473. |
| **B** | `NaicsTaxonomyMapTest`: values ∈ `Taxonomy.SECTORS`; `SECTOR_TO_LEAVES` exact inverse; junk-drawer absent; empty sector → empty list. Post-A: `sector_primary IS NULL` ≈ junk-drawer + tail. |
| **C** | `CompanyDnbQueryServiceTest` (mocked repo): "banking in Saudi Arabia" → SA banks via `depository_credit_intermediation`; pool bounded; sorted by score then `sales_usd`; junk-drawer row only via `primary_industries[]`. |
| **D** | `PipelineUtilsTest`: band boundaries; each of the 14 dirty revenue / 17 dirty employee variants → canonical; null/blank → null. |
| **E** | placeholder-revenue row gets **no** revenue-band match even when the query asks for that band; a reliable row does. |
| **Prompt** | `EnrichmentFilterServiceTest`: existing cases pass (output contract unchanged); niche query ("neobanks in UAE") → broad `primarySectors` (`Banking & Financial Services`) + niche `subTags` (`fintech-neobank`). |
| **E2E** | `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test`; then `mvn spring-boot:run`, hit `/api/search/enhanced-stream?query=…`; toggle `app.search.dnb.enabled`. |
