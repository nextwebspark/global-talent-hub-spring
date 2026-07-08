# Phase 00 — Foundation: schema + master read path

## Goal

Create the new `app_*` portal schema and prove the backend can read the existing `app_companies`
master catalog through one new `/api/app/*` endpoint. End state: apply one SQL file, then
`GET /api/app/companies/{id}` returns a master company as JSON.

## Depends on

Nothing (first phase). DB must already have `app_companies` loaded (`docs/04_app_companies.sql` +
its loader) and the `companies` spine it FKs.

## What to build

**Schema — `docs/05_app_portal.sql`** (authored here; applied by hand; idempotent). Creates the 3
writable, org-scoped `app_*` tables used by phases 02–04, all FK'd to `app_companies.id`
(`BIGINT`):
- `app_search_runs` — `id, org_id(NOT NULL), created_by, query(NOT NULL), mode, parsed_criteria
  jsonb, result_count, status(default 'active'), created_at, updated_at`. Index on `org_id`.
- `app_projects` — `id, org_id, created_by, name(NOT NULL), client_company_id →app_companies(id),
  search_run_id →app_search_runs(id), status(default 'active'), created_at, updated_at`. Indexes on
  `org_id`, `client_company_id`.
- `app_project_companies` — `id, org_id, project_id →app_projects(id) ON DELETE CASCADE, company_id
  →app_companies(id), relevance_type, confidence, status(NOT NULL, default 'untriaged'), map_x,
  map_y, created_at, UNIQUE(project_id, company_id)`. Indexes on `project_id`, `company_id`.
  `status` is the triage state ∈ `untriaged | in_universe | shortlisted | declined`. A freshly-added
  company is `untriaged` (in the list, not yet bucketed); the user then moves it to In universe /
  Shortlisted / Declined. Store as VARCHAR + a `CHECK (status IN (...))`, not a DB enum (keeps
  H2-test parity). Per-project triage counts = `GROUP BY status`.

**Backend (`com.globaltalenthub.*`)** — read path for the master catalog only:
- `entity/AppCompany` — JPA entity mapping the `app_companies` columns from
  `docs/04_app_companies.sql` (**read-only, never written**). Text-array columns (`industry_tags`,
  `sic_codes`, `sic_labels`, `specialties`, `markets`) use `@Type(StringArrayType.class)`. No
  `org_id` (shared catalog).
- `repository/AppCompanyRepository extends JpaRepository<AppCompany, Long>` — no org finders (master
  reads are shared).
- `dto/AppCompanyDto` — flat camelCase record the UI consumes (identity, industry, size/revenue,
  geo, description) + a `from(entity)` mapper. Arrays → `List<String>`.
- `service/AppCompanyService.getById(id)` — returns DTO or throws 404.
- `controller/AppCompanyController` — `GET /api/app/companies/{id}`, takes
  `@AuthenticationPrincipal AuthenticatedUser` (auth required, no org filter on the read).

Copy style from `entity/Company.java`, `repository/CompanyRepository.java`,
`controller/CompanyController.java`.

> `/api/app/**` is not public in `SecurityConfig`, so it already requires a valid JWT — **do not
> touch `SecurityConfig`.**

## Endpoint contract

`GET /api/app/companies/{id}` → `AppCompanyDto`:
```json
{ "id": 1, "name": "…", "website": "…", "primaryIndustry": "…", "industryTags": ["…"],
  "specialties": ["…"], "ownership": "…", "isPublic": true,
  "revenueUsd": 12345678, "revenueRange": "…", "employeeCount": 1234, "employeeRange": "…",
  "hqCountry": "…", "hqCity": "…", "markets": ["…"], "description": "…", "founded": 1998 }
```
Unknown id → HTTP 404 (sanitized JSON via `GlobalExceptionHandler`).

## UI — what to do this phase

No user-facing screen yet — just the client seam so phase 01 has something to call.

**Tasks**
- Add `AppCompany` type to `global-talent-hub-ui/src/lib/api/types.ts` (matches `AppCompanyDto`
  above).
- Add `src/lib/api/appCompanies.ts` with a `useAppCompany(id)` TanStack Query hook →
  `GET /api/app/companies/${id}` (mirror hook style in `src/lib/api/companies.ts`).
- No route/page. Optional throwaway dev check only.

**Design references** (`global-talent-hub-ui/doc/claude-design/ui_kits/talent-map/`)
- `data.jsx` — the company field shape the UI expects (`TM_COMPANIES` objects: name, city,
  country, sector, revenue, employees, confidence, summary). Confirm `AppCompany` covers these.
- `panel.jsx` — `RightPanel` company-detail layout (used in phase 04); note now which
  `AppCompanyDto` fields it needs so the DTO is right the first time.

## Test / verify

- `JAVA_HOME=…jdk-21… mvn test` — add `AppCompanyServiceTest`: persist an `AppCompany` (H2
  auto-creates the table from the entity), `getById` returns matching DTO; unknown id → 404.
- Manual (dev DB, `05_app_portal.sql` applied): `GET /api/app/companies/1` with a bearer token
  returns the JSON above; unknown id → 404.

## Done when

- [ ] `docs/05_app_portal.sql` applies cleanly (idempotent, all 3 tables + indexes).
- [ ] `AppCompany` / repo / DTO / service / controller created.
- [ ] `mvn test` green incl. `AppCompanyServiceTest`.
- [ ] `GET /api/app/companies/{id}` works; unknown → 404.
- [ ] No existing file changed.
