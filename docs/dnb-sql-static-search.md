# DNB + Enrichment — SQL Static Search & Mapping (hybrid Phase 2)

> Scope: **only the SQL static-filter phase** of the hybrid (`LLM intent → hard SQL filter
> on both tables`). Vector re-rank, merge/dedupe and SSE wiring are deliberately **out of
> scope** here (covered in `dnb-integration-analysis.md`). This doc is the contract for:
> *how the one `EnrichmentFilter` becomes a `WHERE` clause on each table*, and *what
> transforms/mappings make the same filter elements queryable against both vocabularies.*

DNB (`company_dnb_qualified`, 18,718 rows) is the **primary** source (golden, accurate).
Enrichment (`company_enrichment`, 5,247 rows) is the **secondary** source. Both are filtered
in parallel from the **same** `EnrichmentFilter` (one LLM intent step, unchanged).

---

## 1. Why mapping is needed (the core problem)

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

## 2. Verified data facts (re-checked against the real CSVs)

| Fact | Number | Consequence for SQL |
|---|---|---|
| DNB rows | 18,718 | must reduce to a bounded pool cheaply, never full-scan |
| DNB distinct `industry` leaves | **178** | mapping table is hand-authored once, finite |
| DNB distinct `parent_industry` | 21 | too coarse/noisy — map the **leaf**, not the parent (F5) |
| DNB rows with `sales_usd` | 18,639 = **99.6%** | revenue band derivable for ~all rows; `sales_usd` is clean integer USD |
| DNB rows null `employees_estimate` | 446 = **2.4%** | employee band null for those → scorer tolerates null |
| DNB countries | SA 6402, QA 5525, KW 2494, BH 2319, OM 1852, **UAE 125**, Pakistan 1 | filter to the 6 GCC in SQL; drops the 1 stray (F3) |
| Top-40 leaf coverage | **87.2%** | mapping 40 leaves already covers most rows |
| Top-100 leaf coverage | **97.6%** | map ~100 leaves → near-total recall |
| Junk-drawer leaf (`administrative_and_support_and_waste_management…`) | **4,029 = 21.5%** | NAICS catch-all, maps to no single sector → **leave unmapped**, reach via `primary_industries[]` + (later) vectors |
| Enrichment `revenue_band` variants | **20** (6 canonical + 14 dirty + 45 blank) | dirty bands silently never match today — normalize (F4) |
| Enrichment `employee_band` variants | **25** (8 canonical + 17 dirty + 82 blank) | same — normalize (F4) |

`sales_usd` correction vs the analysis doc: `sales_usd` IS a clean integer USD value
(e.g. ADCB = 8,498,295,740). Use it directly for banding; ignore `revenue_usd_thousands`.

---

## 3. The mappings (the heart of "map each table")

### 3a. Sector → NAICS leaf set  (`taxonomy/NaicsTaxonomyMap.java`, NEW)

Two static maps, hand-authored once from the 178 leaves:

```java
// leaf NAICS slug  →  ONE taxonomy sector
Map<String,String>        LEAF_TO_SECTOR;   // e.g. "depository_credit_intermediation" → "Banking & Financial Services"
// taxonomy sector  →  all its NAICS leaves   (reverse, derived from LEAF_TO_SECTOR)
Map<String,List<String>>  SECTOR_TO_LEAVES;
```

- Build `LEAF_TO_SECTOR` for the **top ~100 leaves** (97.6% coverage). Junk-drawer leaf →
  **unmapped** (no entry).
- `SECTOR_TO_LEAVES` is derived by inverting `LEAF_TO_SECTOR` once in a static block.
- **Used ONLY as the SQL recall gate** — get candidates into the pool. NOT trusted for final
  ranking (F6: `primary_industries[]` mis-sectors TAQA/Aldar). The later vector phase fixes
  ranking; here a wrong row entering the pool is harmless (it gets demoted downstream).

At query time, expand the filter's sectors into the leaf set the DNB `WHERE` matches against:

```java
List<String> sectorAll = new ArrayList<>(filter.primarySectors());
sectorAll.addAll(filter.adjacentSectors());
List<String> naicsLeaves = sectorAll.stream()
    .flatMap(s -> SECTOR_TO_LEAVES.getOrDefault(s, List.of()).stream())
    .distinct().toList();   // → :naicsLeaves SQL param (Postgres array literal)
```

Seed map (illustrative, not exhaustive — author the full ~100 in the impl):

| NAICS leaf | Taxonomy sector |
|---|---|
| `depository_credit_intermediation` | Banking & Financial Services |
| `mining_quarrying_and_oil_and_gas_extraction` | Oil & Gas - Upstream |
| `construction`, `highway_street_and_bridge_construction` | Construction & Engineering |
| `architectural_engineering_and_related_services` | Construction & Engineering |
| `professional_scientific_and_technical_services` | Professional Services |
| `accommodation_and_food_services`, `travel_arrangement_and_reservation_services` | Hospitality, Travel & Tourism |
| `iron_and_steel_mills_and_ferroalloy_manufacturing`, `manufacturing` | Manufacturing & Industrial |
| `administrative_and_support_and_waste_management…` | **(unmapped — junk drawer)** |

> Open decision (carried from analysis §7): the mining/O&G leaf (1,021 rows) maps simply to
> *Oil & Gas - Upstream*; refine via `primary_industries[]` later if precision demands it.

### 3b. Revenue / employee → bands  (`PipelineUtils`, NEW helpers)

Shared by BOTH tables — one source of truth for banding:

```java
// raw USD → one Taxonomy.REVENUE_BANDS value (cutoffs: <10M, 10-50M, 50-250M, 250M-1B, 1-10B, >10B)
static String revenueBandFor(Long usd);
// dirty enrichment band string → snap to canonical (fallback when revenue_estimate_usd is null)
static String normalizeRevenueBand(String raw);
// raw headcount → one Taxonomy.EMPLOYEE_BANDS value (1-10 … 10k+); null → null (scorer tolerates)
static String employeeBandFor(Integer count);
```

- **DNB**: `revenueBandFor(sales_usd)`, `employeeBandFor(employees_estimate)` — DNB has no band column.
- **Enrichment**: prefer `revenueBandFor(revenue_estimate_usd)`; fall back to
  `normalizeRevenueBand(revenue_band)` when the estimate is null. Fixes the 14 dirty
  variants that silently never match the scorer today.
- Band cutoffs are the single canonical definition; keep them aligned with `Taxonomy.REVENUE_BANDS`
  / `EMPLOYEE_BANDS` exactly.

### 3c. Listed → `is_private` invert  (DNB only)

`isListed = NOT is_private`. In SQL: `(:isListed IS NULL OR (NOT is_private) = :isListed)`.

### 3d. Country — no mapping (F3)

DNB `country` already uses the identical canonical strings as `Taxonomy.COUNTRIES`.
Filter to the 6 GCC directly; this also drops the 1 Pakistan stray.

---

## 4. The two SQL queries (same filter, two vocabularies)

Soft signals (country/band/listed) follow the existing enrichment convention: they **bias
the pool ordering**, the Java `CompanyScore` does the precise weighting — they do **not**
hard-exclude rows (a null band must never drop a golden DNB row). Country, however, is
pushed into the pool ordering so a thin requested country is not crowded out (matches the
existing enrichment behavior at `CompanyEnrichmentRepository:45`).

Pool size reuses the existing rule: `pool = min(500, max(limit*5, 100))`
(`CompanyEnrichmentQueryService:43`).

### 4a. Enrichment — identity mapping (extend existing `queryCandidatePool`)

Already implemented and correct — **no sector change needed**. The only enhancement is using
normalized bands in the Java scorer (3b), not in this SQL. Leave the candidate-pool SQL as is.

```sql
-- company_enrichment (UNCHANGED recall gate; sector vocab == taxonomy)
WHERE (
  (:hasSector  AND primary_sector = ANY(:sectorAll))
  OR (:hasSector  AND sector_tags && :sectorAll)
  OR (:hasSubTags AND sub_tags   && :subTags)
)
ORDER BY (country = ANY(:countries)) DESC, confidence DESC
LIMIT :pool
```

### 4b. DNB — NAICS mapping gate (`CompanyDnbQualifiedRepository.queryCandidatePool`, NEW)

```sql
-- company_dnb_qualified (NAICS vocab; sector via mapping table → :naicsLeaves)
SELECT id, duns, name, country, industry, industry_name, primary_industries,
       sales_usd, employees_estimate, is_private, stock_exchange, website, raw_context
FROM company_dnb_qualified
WHERE country = ANY(:gcc)                                  -- F3: 6 GCC only, drops stray row
  AND (
    (:hasSector AND industry = ANY(:naicsLeaves))          -- primary leaf match
    OR (:hasSector AND primary_industries && :naicsLeaves) -- richer recall (F6), array overlap
  )
ORDER BY (country = ANY(:countries)) DESC, sales_usd DESC NULLS LAST  -- golden proxy: bigger first
LIMIT :pool
```

- `:gcc` = the 6 `Taxonomy.COUNTRIES`; `:countries` = the filter's requested subset (ordering bias).
- `:naicsLeaves` = expansion from 3a. Bands are **not** in this SQL — derived in Java per 3b and
  fed to the scorer (so a null/edge band never excludes a golden row).
- Array params passed as Postgres array literals via the existing
  `CompanyEnrichmentQueryService.toArrayLiteral(...)` helper (reuse it).

---

## 5. Java side — reuse the existing scorer for DNB

`CompanyScore.score(ScorableRow, EnrichmentFilter)` is source-agnostic. A DNB row maps to the
same `ScorableRow` shape — **no second scorer**:

```java
// DnbRow.toScorable()  (DnbRow = NEW, mirrors EnrichedRow for the DNB projection)
new CompanyScore.ScorableRow(
    NaicsTaxonomyMap.LEAF_TO_SECTOR.getOrDefault(industry, null),         // primarySector
    primaryIndustries.stream()                                           // sectorTags
        .map(NaicsTaxonomyMap.LEAF_TO_SECTOR::get).filter(Objects::nonNull).distinct().toList(),
    List.of(),                                                           // sectorMix (DNB has none — F2)
    List.of(),                                                           // subTags  (DNB has none — F1)
    country,
    PipelineUtils.revenueBandFor(salesUsd),                             // derived (3b)
    PipelineUtils.employeeBandFor(employeesEstimate),                  // derived (3b)
    !isPrivate);                                                        // isListed (3c)
```

Because DNB has empty `sectorMix`/`subTags`, its sector signal comes only from
`primarySector`/`sectorTags` tiers — coarser than enrichment (F2), as expected for the SQL
phase. The vector phase (out of scope here) restores fine-grained relevance.

New query service `CompanyDnbQueryService` mirrors `CompanyEnrichmentQueryService`: call repo
→ map rows → score → sort by `matchScore` (then `sales_usd` desc as the golden tiebreak,
vs `confidence` for enrichment) → `limit`.

---

## 6. Files

**New**
- `taxonomy/NaicsTaxonomyMap.java` — `LEAF_TO_SECTOR` (~100 leaves) + derived `SECTOR_TO_LEAVES`.
- `entity/CompanyDnbQualified.java` — JPA entity (`ddl-auto=none`, table already external).
- `repository/CompanyDnbQualifiedRepository.java` — `queryCandidatePool` (§4b).
- `service/pipeline/DnbRow.java` — projection record + `toScorable()` (§5).
- `service/pipeline/CompanyDnbQueryService.java` — mirror of `CompanyEnrichmentQueryService`.

**Modify**
- `service/pipeline/PipelineUtils.java` — add `revenueBandFor` / `normalizeRevenueBand` / `employeeBandFor` (§3b).
- `service/pipeline/CompanyEnrichmentQueryService.java` — feed normalized bands to the scorer (§3b);
  candidate-pool SQL unchanged.
- `application.properties` — `app.search.dnb.enabled` flag (toggle DNB source independently).

**Reuse unchanged**
- `EnrichmentFilterService`, `EnrichmentFilter`, `Taxonomy`, `CompanyScore`,
  `CompanyEnrichmentQueryService.toArrayLiteral`, the pool-size rule.

---

## 7. Verification (SQL/mapping scope only)

- **`NaicsTaxonomyMapTest`** — every `LEAF_TO_SECTOR` value ∈ `Taxonomy.SECTORS`;
  `SECTOR_TO_LEAVES` is the exact inverse; junk-drawer leaf has no entry; a sector with no
  leaves expands to empty (no NPE).
- **`PipelineUtilsTest`** — `revenueBandFor` / `employeeBandFor` boundary values land in the
  right `Taxonomy` band; `normalizeRevenueBand` snaps each of the 14 dirty variants to canonical;
  null/blank → null.
- **`CompanyDnbQueryServiceTest`** — mocked repo returns DNB projection rows → assert sector
  maps correctly, bands derive, `isListed = !is_private`, sorted by score then `sales_usd`,
  bounded to `pool`. Include a junk-drawer row reached only via `primary_industries[]`.
- **Data sanity (manual, against target DB)** — `:naicsLeaves` expansion for
  "banking in Saudi Arabia" returns `depository_credit_intermediation` rows; the 6-GCC filter
  drops the Pakistan row; `sales_usd DESC` surfaces the golden large companies first.
- **Run:** `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test`.
</content>
</invoke>
