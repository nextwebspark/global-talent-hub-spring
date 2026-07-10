# v2 — `app_*` Search → Universe → Project (master tracker)

New, isolated fullstack slice built on the existing **`app_companies`** master table
(`docs/04_app_companies.sql`, ~54,044 read-only rows). Adds new `app_*` portal tables + a new
`/api/app/*` REST surface + the matching UI, **without changing existing Spring/API code**
(existing tables are `hak_*`; superseded methods get an additive `// OUTDATED:` comment only —
never edited or deleted).

These docs are **architecture + task tracking**, not code. Code is written during the
implementation session; each phase file says *what to build and how to prove it*, not *how to type
it*. This README is the single global view of the whole build — track end-to-end progress here.

## Scope

- In: **AI/search → company universe (discovery + mapping) → project ("search map") under a client
  company**.
- Out this round: **executives/employees**; the CRM layer (Accounts, Mandates, BD, Contacts,
  Pipeline, Long list, Strategy).

## Phases + status

| Phase | File | Delivers | Status |
|------|------|----------|--------|
| 00 | `phase-00-foundation.md` | `app_*` schema DDL + `AppCompany` read entity + `GET /api/app/companies/{id}` | ☑ done |
| 01 | `phase-01-company-search.md` | SQL filter search + facets over `app_companies` | ☑ done |
| 02 | `phase-02-search-runs.md` | LLM (Vertex AI) intent extraction + persist a search run (`app_search_runs`) | ☑ done |
| 03 | `phase-03-projects.md` | Confirm universe → project under a client (`app_clients` + `app_projects` + join) | ☑ done |
| 04 | `phase-04-project-companies.md` | Project universe list + per-company edit (triage status / relevance / map position) | ☑ done |
| 05 | `phase-05-outdated-comment-pass.md` | Mark superseded existing methods (`// NOTE:` — still UI-used) | ☑ done |
| 06 | `phase-06-pgvector-later.md` | (deferred) semantic search via pgvector | ☐ deferred |

Update the Status column as phases land (☐ → ◐ in progress → ☑ done).

### Build log (what actually landed)

- **Phase 00** — `docs/05_app_portal.sql` (3 portal tables, idempotent). `entity/AppCompany`
  (read-only map of `app_companies`), `repository/AppCompanyRepository`, `dto/AppCompanyDto`,
  `service/AppCompanyService.getById`, `controller/AppCompanyController` `GET /api/app/companies/{id}`.
  `AppCompanyServiceTest` (mapping + 404). SQL applied to dev DB by hand.
- **Phase 01** — `search` + `facets` over `app_companies`. `AppCompanyRepository` now extends
  `JpaSpecificationExecutor` + 4 native grouped-count facet queries (`FacetRow` projection).
  `repository/AppCompanySpecs` (composable optional filters), `dto/FacetCount` + `dto/FacetsDto`,
  service `search()` (sort whitelist `name|revenueUsd|employeeCount|founded`, default `revenueUsd,desc`,
  size cap 100) + `facets()`, controller `GET /api/app/companies/search` + `/facets`.
  `AppCompanyServiceTest` extended (sort whitelist, unknown-sort 400, size cap, facet mapping).
- **Phase 02** — search runs + LLM intent. `taxonomy/AppSearchVocab` (real fixed enums: 6 ISO-2
  countries + 7 revenue + 8 employee bands + country aliases). `entity/AppSearchRun` (jsonb
  `parsed_criteria` as `Map`, copy of `SearchQuery` mapping), `repository/AppSearchRunRepository`
  (`findByIdAndOrgId`). `dto/SearchCriteria` (one combined object: 4 query-active + 3 people keys, with
  `merge()` = client-wins), `CreateSearchRunRequest(query, mode, criteria?)`, `SearchRunDto`.
  `service/AppSearchIntentService.parse` (reuses `LlmService.callWithFallback`; system prompt +
  fenced untrusted query; validates country/revenue/employee vs `AppSearchVocab`, drops off-vocab;
  industry free; **fail-open** to empty on timeout/parse error; skips `Import a list`).
  `service/AppSearchRunService` (create → parse + merge client + persist; get 404; setResultCount),
  `controller/AppSearchRunController` (POST/GET/PATCH). Tests: `AppSearchIntentServiceTest` (11),
  `AppSearchRunServiceTest` (8) — all Mockito (LLM mocked, no context/H2). People keys stored, never
  searched. **AI adjacent-industry suggestions deferred.**
  Note: `AppSearchIntentService` injects `LlmService` (a `@Profile("!test")` bean) by constructor —
  fine because no `mvn test` boots the full app context (only the gated `local`-profile IT does), same
  as the existing pipeline services.
- **Phase 03** — clients + projects. Adopts the **app_clients** model (client is org-owned, not a
  catalog FK). Entities `AppClient`, `AppProject` (`client_id`), `AppProjectCompany`. Repos
  `AppClientRepository` (`findByIdAndOrgId`, `findByOrgIdAndLinkedCompanyId`, name search),
  `AppProjectRepository` (`findByOrgId` paged), `AppProjectCompanyRepository` (paged + single + count).
  DTOs `ClientRef(clientId|newClient)`, `NewClientInput`, `ClientDto`, `CreateProjectRequest`,
  `ProjectCompanyInput/Dto`, `ProjectSummaryDto`, `ProjectDetailDto`. `AppClientService.resolveOrCreate`
  (existing clientId OR new client; validates catalog link; dedupes linked client per org),
  `AppProjectService` (create → resolve client + validate run + insert project & join rows → detail;
  list with count+clientName; detail with client inlined + universe joined). Controllers
  `AppClientController` (`/api/app/clients` GET/POST/GET{id}), `AppProjectController`
  (`/api/app/projects` POST/GET/GET{id}). Tests: `AppClientServiceTest` (9), `AppProjectServiceTest`
  (5) — Mockito. **Full suite green: 192 tests, 0 failures** (60 new app_* + 132 existing).
- **Phase 04** — project universe list + per-company edit (no new entity). `dto/PatchProjectCompanyRequest`.
  `AppProjectService.listCompanies` (paged, project-in-org guarded, joins display fields) +
  `patchCompany` (partial: `status`/`relevanceType` validated against the allowed sets → 400,
  `mapX`/`mapY`; 404 if project-not-in-org or company-not-in-project). `AppProjectController` adds
  `GET /api/app/projects/{id}/companies` + `PATCH .../companies/{companyId}`. `AppProjectServiceTest`
  extended (13 total): paging, partial patch, invalid status/relevance 400s, 404s.
- **Phase 05** — superseded-code marking, **comment-only**. Used **`// NOTE:` not `// OUTDATED:`**
  because every legacy endpoint is **still called by the current UI** (`/api/companies`, `/api/search/*`,
  `/api/search-queries/*` — verified by grepping `global-talent-hub-ui/src` + backend tests). Per the
  phase rule, still-used → NOTE (points at the v2 `/api/app/*` replacement), not OUTDATED. Marked:
  `CompanyController.getAll/search/getOne`, `SearchController.enhancedStream/addToProject`,
  `SearchQueryController` (class). Diff is comment-lines only; **full suite green: 200 tests, 0
  failures** (+8 phase-04). No behavior touched.

### Testing limitation (H2) — important for every phase

`app_companies` (and the portal entities) use Postgres `text[]` + `jsonb` columns. **H2 cannot build
those tables** — `create-drop` chokes on `text[]`/`jsonb` DDL (verified: Hibernate logs a
`GenerationTarget` warning and silently skips the table, so any `@DataJpaTest` / `@SpringBootTest`
that persists these entities fails at `repo.save`). There is **no Testcontainers** on the classpath.
Consequence, matching every existing service test in this repo:

- Backend tests are **service-level with the repository mocked** (Mockito). They verify the service
  logic (sort whitelist, size cap, DTO mapping, facet assembly) — **not** live SQL.
- **Real SQL correctness** (filter composition, `IN`/`LIKE` behaviour, facet counts, pagination
  order) is verified by the **manual curl against the dev Postgres** listed in each phase's
  Test/verify section — not by a unit test.
- Queries use portable `LOWER(col) LIKE LOWER('%term%')` (no Postgres-only `ILIKE` keyword) and JPA
  `Specification`, so they stay engine-agnostic even though only Postgres is exercised.

## Confirmed decisions

- New `app_*` tables, **same Postgres**, FK to `app_companies.id`.
- Phase-1 search = **plain SQL filters** on `app_companies`; **pgvector is a later phase**.
- **LLM (Vertex AI) intent extraction lives in phase 02** (`POST /api/app/search-runs`): raw query →
  structured `parsed_criteria`, which feeds the phase-01 SQL search. **Reuse the existing
  `LlmService`/`geminiFlash` gateway** (fail-open on timeout) — no new LLM wiring, no SSE
  (request/response only).
- **Criteria map to REAL `app_companies` values, NOT the old `Taxonomy.java`** (which is the SSE
  pipeline's vocab and does not match this table). `app_companies` is a **GCC dataset**: `hq_country`
  = 6 ISO-2 codes (`AE,SA,QA,KW,OM,BH`), `revenue_range`/`employee_range` = fixed band strings,
  `primary_industry` = 523 free labels (ILIKE-matched, not a set). New `taxonomy/AppSearchVocab`
  holds the small fixed enums. Live-probed values recorded in memory `app-companies-real-vocab`.
- Superseded existing code: **comment only** (`// OUTDATED: …`), never edit/delete.
- `app_companies` is a **shared master catalog** — reads are NOT org-scoped. Only the new writable
  `app_*` tables carry `org_id` and are org-scoped.
- **6-step Search Map Wizard** (`search-wizard.jsx`) is the search entry; new user (no projects) →
  full page, returning → modal — UI routing only, both hit the same `POST /api/app/search-runs`.
  The wizard captures **position & experience** (step 4) → stored on the run but **not searched**
  (no employee data yet). **AI adjacent-industry suggestions = deferred** to a later iteration.
- **Client is `app_clients`, not a catalog FK** (adopted from `docs/new-schema.sql` in the db review).
  A project's client is the org's own record (`app_projects.client_id → app_clients`), with an
  optional `linked_company_id → app_companies` for display. Supports clients not in the vendor scrape.
- **Hardened portal DDL** (`docs/05_app_portal.sql`) carries NOT NULLs + `CHECK` sets
  (`status`, `relevance_type ∈ Direct/Adjacent/AI Inferred`, `confidence 0–100`) + explicit named FKs
  (`ON DELETE RESTRICT`/`CASCADE`) + extra indexes; all guarded/idempotent. Supporting SQL:
  `docs/new-schema.sql` (client-model rationale), `docs/new-schema-migration.sql` (cutover for a DB
  still on the old `client_company_id`), `docs/05_app_portal_sample_data.sql` + `docs/user_search.sql`
  (dev seed + a phase-by-phase SQL walkthrough).

## Backend conventions (apply to every phase)

- Stack: Spring Boot 3.5.15, Java 21, Maven, Spring Data JPA, Postgres (H2 in tests), Lombok,
  hypersistence-utils. Root package `com.globaltalenthub`. Layering
  Controller → Service → Repository → Entity.
- `ddl-auto=none` (dev/prod) — **schema external, no Flyway**. New tables applied by hand from
  `docs/*.sql`. Entities only map columns. Test profile = H2 `create-drop`.
- Controllers `@RestController @RequiredArgsConstructor`; handlers take
  `@AuthenticationPrincipal AuthenticatedUser user` → `user.orgId()` / `user.userId()`.
  `AuthenticatedUser(UUID userId, String email, UUID orgId, String orgRole)`.
- Writable `app_*` tables carry `org_id`; repos use `findBy…AndOrgId`; guard cross-entity access
  like `OrgGuardService` (`findByIdAndOrgId(...).orElseThrow(404)`).
- `text[]` → `@Type(StringArrayType.class)`; `jsonb` → `@Type(JsonBinaryType.class)`; timestamps in
  `@PrePersist/@PreUpdate`.
- Reuse `web/GlobalExceptionHandler`; throw `ResponseStatusException` for 400/404.
- Files to copy for style: `controller/CompanyController.java`, `repository/CompanyRepository.java`,
  `entity/Company.java`, `entity/SearchQuery.java`, `security/AuthenticatedUser.java`,
  `service/OrgGuardService.java`, `web/GlobalExceptionHandler.java`.

## New tables (DDL authored once in phase 00 → `docs/05_app_portal.sql`)

- `app_search_runs` — one search execution (raw query + **LLM-parsed** criteria + result count +
  status). `parsed_criteria` (one jsonb) = the LLM parse **merged with the wizard's edited criteria**
  (client wins per field). Holds query-active keys (`industry/country/revenueRange/employeeRange`,
  used by phase-01 `/search`) **and** captured-but-unsearched people keys
  (`positions/seniority/experience`, stored only — no employee data yet).
- `app_clients` — the org's **own client relationships** (who a project is FOR). `name` always
  present; `linked_company_id → app_companies.id` is an **optional** display-enrichment link.
  `UNIQUE(org_id, linked_company_id)`. Lets a real client the vendor scrape never captured still back
  a project. (Adopted from `docs/new-schema.sql`; supersedes the old direct `client_company_id`.)
- `app_projects` — the "search map" workspace; belongs to a **client** (`client_id → app_clients.id`,
  NOT a catalog company), originates from a run (`search_run_id → app_search_runs.id`).
- `app_project_companies` — join of a project's universe to master companies + per-company mapping
  state (`status` triage ∈ `untriaged|in_universe|shortlisted|declined`, default `untriaged`;
  relevance; map x/y). `UNIQUE(project_id, company_id)`. Sidebar buckets (In universe / Shortlisted
  / Declined) = `GROUP BY status`; new rows start `untriaged` (in the list, not yet bucketed).

## New API surface (all `/api/app/*`, JSON; master reads not org-scoped)

```
GET   /api/app/companies/search   ?q&industry[]&country[]&revenueRange[]&employeeRange[]&sort&page&size
GET   /api/app/companies/facets   (same filter params → grouped counts)
GET   /api/app/companies/{id}
POST  /api/app/search-runs        {query, mode}  → LLM parses query → parsed_criteria + runId
PATCH /api/app/search-runs/{id}   {resultCount}   (write back after the search resolves)
GET   /api/app/search-runs/{id}
GET   /api/app/clients            ?q            (org's own clients)
POST  /api/app/clients            {name, linkedCompanyId?}
GET   /api/app/clients/{id}
POST  /api/app/projects           {name, client:{clientId|newClient}, searchRunId, companies[]}
GET   /api/app/projects           ?page&size
GET   /api/app/projects/{id}
GET   /api/app/projects/{id}/companies   ?page&size
PATCH /api/app/projects/{id}/companies/{companyId}
```

Paths do not collide with existing `/api/companies`, `/api/search`, `/api/search-queries`. UI's
global `authFetch` attaches base URL + bearer; call sites use bare `/api/app/*`.

## UI (design reference only — never spec UI implementation in these docs)

- Design source: `global-talent-hub-ui/doc/claude-design/ui_kits/talent-map/*.jsx` (interactive
  kit) + `data.jsx` (field shapes). Brand rules: `doc/claude-design/SKILL.md` + `colors_and_type.css`.
- Target app: `global-talent-hub-ui` (React 19 + Vite SPA, wouter, TanStack Query, Zustand,
  shadcn/ui, Tailwind v4). API clients `src/lib/api/*.ts`; types `src/lib/api/types.ts`. Feature
  folders: `src/features/{landing,universe,projects}`.
- Gates: `npm run check` (tsc strict), `npm run test:unit` (vitest).

## Global end-to-end verification

- Backend build/test: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test`.
- Manual dev run (DB has `app_companies` + `app_*` applied): search → facets → create run → create
  project (client + companies) → project detail → patch a company → re-read.
- UI: `npm run check` + `npm run test:unit`; click-through landing → universe → confirm → project.
- Confirm **zero edits** to existing `hak_*` code beyond `// OUTDATED:` comments (phase 05).

## End-to-end data flow (the thread across phases)

```
landing: user types a raw query
      │
      ▼ POST /api/app/search-runs {query, mode}
LLM (Vertex AI) parses query ──► parsed_criteria + app_search_runs row   (phase 02)
      │  (returns runId + criteria; UI shows editable scope chips)
      ▼ run the search with those criteria
GET /api/app/companies/search + /facets                                  (phase 01, over app_companies)
      │  (PATCH the run with resultCount)
      ▼ user confirms universe (pick client + selected companies)
POST /api/app/projects     ──► app_projects + app_project_companies       (phase 03)
      │
      ▼ open project workspace
GET  /api/app/projects/{id}/companies                                     (phase 04)
PATCH .../companies/{companyId}  (status triage / relevance / map x,y)    (phase 04)
```

> Phase **numbers** stay build-order (01 SQL search ships before 02's LLM layer that feeds it, so 01
> is testable standalone). **Runtime order** is 02 → 01: the LLM parses the query first, then the SQL
> search uses the criteria. Phase 01 accepts criteria as plain params, so it doesn't depend on 02 to
> be built or tested.
