# Phase 01 — Company search + facets over `app_companies`

## Goal

Paginated SQL search/filter over the `app_companies` master catalog + a facets endpoint returning
filter counts for the scope sidebar. Plain SQL only — **no LLM, no vectors** this phase.

## Depends on

Phase 00 (`AppCompany` entity, repo, DTO exist).

## What to build (backend)

- **`AppCompanyRepository`** — add:
  - `search(...)` — paginated (`Pageable`) filter query. Scalar filters, each ignorable when
    empty: `q` ILIKE on `name` + `search_text`; `primary_industry IN`; `hq_country IN`;
    `revenue_range IN`; `employee_range IN`. Compose AND across fields, OR within a field.
  - facet count queries: group-by-count over `primary_industry`, `hq_country`, `revenue_range`,
    `employee_range`.
- **Array-tag note**: `industry_tags && :tags` (Postgres array overlap) is **Postgres-only and not
  H2-testable**. For phase 01, match `industry` against **`primary_industry` (scalar, H2-safe)**;
  richer `industry_tags` overlap is deferred to the pgvector phase. State the choice in the PR.
- **`AppCompanyService`** — `search(...)` (build `Pageable` + **sort whitelist**: only
  `name`/`revenueUsd`/`employeeCount`/`founded`; default `revenueUsd,desc`; reject others) and
  `facets()`. Cap `size` at 100.
- **DTOs** — `FacetCount(value, count)`, `FacetsDto(industries, countries, revenueRanges,
  employeeRanges)`.
- **`AppCompanyController`** — add `GET /api/app/companies/search` + `GET /api/app/companies/facets`.

> Phase-01 facets are **global** (whole catalog) — enough for the initial sidebar. Facets that
> react to the current filter selection are a later refinement.

## Endpoint contracts

**`GET /api/app/companies/search`** — params (all optional; repeatable = OR within field):

| param | type | default | meaning |
|------|------|---------|---------|
| `q` | string | — | ILIKE `name` + `search_text` |
| `industry` | string[] | — | `primary_industry IN` |
| `country` | string[] | — | `hq_country IN` |
| `revenueRange` | string[] | — | `revenue_range IN` |
| `employeeRange` | string[] | — | `employee_range IN` |
| `sort` | `field,dir` | `revenueUsd,desc` | field ∈ {name,revenueUsd,employeeCount,founded} |
| `page` | int | 0 | 0-based |
| `size` | int | 25 | ≤100 |

→ Spring `Page<AppCompanyDto>` (`content[]`, `totalElements`, `totalPages`, `number`, `size`,
`first`, `last`).

**`GET /api/app/companies/facets`** →
```json
{ "industries": [{"value":"Banking & Financial Services","count":812}],
  "countries": [{"value":"Saudi Arabia","count":6402}],
  "revenueRanges": [{"value":"$1B–5B","count":340}],
  "employeeRanges": [{"value":"5K–10K","count":210}] }
```

## UI — what to do this phase

Wire the **search entry → results table → scope sidebar** to the two new endpoints. This is the
core discovery screen.

**Tasks**
- API client: extend `src/lib/api/appCompanies.ts` with `useCompanySearch(params)` (paged) and
  `useCompanyFacets()`; add param + response types to `src/lib/api/types.ts`. Mirror
  `src/lib/api/search.ts`.
- Landing/search entry (`src/features/landing/`): free-text query box + suggestion chips + the 3
  mode cards; on submit, run a search (Phase 1 = call `/api/app/companies/search` with the query as
  `q`). Import-list / From-brief modes are stubs this phase (route to the same search).
- Universe results (`src/features/universe/`): render the paged company table (name, sector/industry,
  country, revenue, employees, relevance, confidence), pagination, compact/comfortable density
  toggle.
- Scope/filter sidebar: accordion of Sectors / Countries / Revenue / Relevance driven by
  `/api/app/companies/facets`; toggling a facet re-queries search with the selected filters and
  shows counts.

**Design references** (`doc/claude-design/ui_kits/talent-map/`)
- `landing.jsx` — `Landing({ onDiscover, ... })`: hero, mode cards (`search` / `import` /
  `brief` at lines 11–13), suggestion chips from `TM_SUGGESTIONS`, the `Describe what you're
  looking for…` textarea + `Discover Companies` button (submit at lines 16–18). Copy this layout.
- `universe.jsx` — `UniverseView`: the discovery results container, compact/comfortable toggle
  (lines 217–220), source-run tabs. (Streaming/confirm parts belong to phases 02–03; here just the
  table + filters.)
- `tableview.jsx` — `TableView`: dense company × row grid, sortable headers (`SortIc`), row toggles
  (`Toggle`), bulk bar (`BulkBar`). Column set + sort UX to mirror.
- `universe-filters.jsx` — `UniverseFilters` + `ScopeSection` / `ScopeCheckbox`: the collapsible
  facet sidebar with per-option counts and active-filter badge; master option lists in `data.jsx`
  (`TM_MASTER_COUNTRIES`, `TM_MASTER_SECTORS`, `TM_MASTER_REVENUE`, `TM_MASTER_RELEVANCE`). Maps
  directly onto the facets response.

## Test / verify

- `JAVA_HOME=…jdk-21… mvn test -Dtest=AppCompanySearchTest` — seed ~5 companies spanning 2
  countries / 2 revenue ranges / 2 industries; assert: `q` narrows (case-insensitive); single +
  multiple `country` (OR); `country`+`revenueRange` compose (AND); pagination `size=2` totals +
  stable order; `sort=name,asc` vs unknown-sort fallback; facet counts equal seeded groups.
- Manual: `GET /api/app/companies/search?q=bank&country=Saudi%20Arabia&sort=revenueUsd,desc&page=0&size=20`
  and `GET /api/app/companies/facets`.
- UI: `npm run check`; results list renders filtered + paged against a running backend.

## Done when

- [ ] `search` + `facets` repo queries, service (with sort whitelist), controller endpoints exist.
- [ ] `AppCompanySearchTest` green (filters compose, pagination stable, facet counts correct).
- [ ] Manual search + facets return expected shapes.
- [ ] UI search entry + results table + facet sidebar wired to the endpoints (`npm run check` clean).
- [ ] No existing backend file changed.
