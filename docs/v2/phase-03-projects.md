# Phase 03 — Confirm universe → project under a client

## Goal

Turn a confirmed universe into a persistent **project** ("search map"): create an `app_projects`
row (under a **client** + originating search run) plus one `app_project_companies` join row per
selected company. Expose list + detail. This is the "confirm universe" → workspace step.

> **Client model (adopted — see `docs/new-schema.sql`).** A project's client is **its own
> org-owned record** in **`app_clients`**, NOT a direct `app_companies` FK. A client always has a
> `name`; `linked_company_id → app_companies(id)` is an **optional** display-enrichment link (logo/
> industry/HQ) for when the client also happens to be a catalog row. This lets a real client the
> vendor scrape never captured still back a project. `app_projects.client_id → app_clients(id)`.

## Depends on

- Phase 00 / DDL (`app_clients`, `app_projects`, `app_project_companies` in `docs/05_app_portal.sql`).
- Phase 02 (`app_search_runs` + a run id to attach).
- Phase 01 (company ids come from search results; the client picker reuses `/search` with only `q`).

## What to build (backend)

- **`entity/AppClient`** — `id, org_id, created_by, name, linked_company_id (→app_companies.id,
  nullable), created_at, updated_at`. Timestamps in lifecycle hooks. Org-scoped.
- **`entity/AppProject`** — `id, org_id, created_by, name, client_id (→app_clients.id),
  search_run_id (→app_search_runs.id), status, created_at, updated_at`. Timestamps + default
  `status='active'` in lifecycle hooks. (`status` ∈ `active | archived` — matches DDL `CHECK`.)
- **`entity/AppProjectCompany`** — `id, org_id, project_id, company_id, relevance_type, confidence,
  status(default 'untriaged'), map_x, map_y, created_at`. `@UniqueConstraint(project_id,
  company_id)`. `status` ∈ `untriaged | in_universe | shortlisted | declined` (triage state; String,
  not JPA enum — matches the DDL `CHECK`). New rows default `untriaged`. `relevance_type` is NOT
  NULL, default `Direct`, ∈ `Direct | Adjacent | AI Inferred` (DDL `CHECK`); `confidence` ∈ 0–100.
- **Repositories** — `AppClientRepository`: `findByIdAndOrgId`, `findByOrgIdAndLinkedCompanyId`
  (dedupe a linked client per org). `AppProjectRepository`: `findByOrgId(orgId, Pageable)`,
  `findByIdAndOrgId`. `AppProjectCompanyRepository`: `findByProjectIdAndOrgId`,
  `countByProjectIdAndOrgId`, `findByProjectIdAndCompanyIdAndOrgId`.
- **DTOs**
  - `CreateProjectRequest(name, client, searchRunId, companies[])` where `client` is a
    **`ClientRef(clientId?, newClient?)`** — supply **either** an existing `clientId` **or**
    `newClient{ name, linkedCompanyId? }` to create one (exactly one of the two → else 400). Each
    company = `ProjectCompanyInput(companyId, relevanceType, confidence, mapX, mapY)`.
  - `ClientDto(id, name, linkedCompanyId, linkedCompany (AppCompanyDto|null))` — the resolved client,
    with the catalog row inlined for display when linked.
  - `ProjectSummaryDto(id, name, clientId, clientName, companyCount, status, createdAt)`.
  - `ProjectDetailDto(id, name, status, searchRunId, client (ClientDto), companies[])` where each
    `ProjectCompanyDto(id, companyId, name, primaryIndustry, hqCountry, revenueUsd, relevanceType,
    confidence, status, mapX, mapY)` (`status` ∈ `untriaged|in_universe|shortlisted|declined`; new
    rows default `untriaged`).
- **`service/AppClientService`** — `resolveOrCreate(ClientRef, user) → AppClient` (used by project
  create): if `clientId` given, load via `findByIdAndOrgId` (400/404 if not in org); else create an
  `app_clients` row from `newClient` (validate `name` non-blank; if `linkedCompanyId` given, validate
  it exists in `app_companies` (400) and reuse an existing linked client for the org via
  `findByOrgIdAndLinkedCompanyId` to respect `UNIQUE(org_id, linked_company_id)`). Plus
  `get`/`list`/simple `create` for a standalone client endpoint (below).
- **`service/AppProjectService`** (`@Transactional` create):
  - `create` — validate name non-blank (400); **resolve the client** via `AppClientService`;
    validate `searchRunId` in caller's org (400); insert project + one join row per input company;
    return detail.
  - `list` — `findByOrgId` → summaries, filling `companyCount` (count query) + `clientName`
    (from `app_clients`).
  - `detail` — `findByIdAndOrgId` (404); load join rows + join each to `AppCompany` for display;
    load the `app_clients` row → `ClientDto` (inlining its `linked_company_id`'s `AppCompany` when set).
  - Inject `AppClientRepository`/`AppClientService`, `AppProjectRepository`,
    `AppProjectCompanyRepository`, `AppSearchRunRepository`, `AppCompanyRepository`.
- **`controller/AppProjectController`** — `POST /api/app/projects` (201),
  `GET /api/app/projects?page&size` (sort by `updatedAt` desc), `GET /api/app/projects/{id}`.
- **`controller/AppClientController`** — a small client surface the picker uses:
  `GET /api/app/clients?q` (org's own clients, name ILIKE), `POST /api/app/clients`
  `{name, linkedCompanyId?}` (201), `GET /api/app/clients/{id}`.

Model note (design): a project row is `{ id, name, clientId, companies(count), active }`
(`data.jsx` `TM_PROJECTS`). `execs` count is out of scope (executives skipped) — omit or `0`.
`relevance_type` ∈ `Direct | Adjacent | AI Inferred`.

## Endpoint contracts

**`POST /api/app/projects`** (201) — `client` is either an existing `clientId` OR a `newClient`:
```json
// request — link a NEW client to a catalog company (id 501) while creating the project:
{ "name": "Top FMCG distributors in UAE",
  "client": { "newClient": { "name": "Al Rabie Saudi Foods Co.", "linkedCompanyId": 501 } },
  "searchRunId": 42,
  "companies": [ { "companyId": 12, "relevanceType": "Direct", "confidence": 91, "mapX": 52, "mapY": 50 },
                 { "companyId": 18, "relevanceType": "Adjacent", "confidence": 74 } ] }
// — or reference an existing client the org already has:
{ "name": "…", "client": { "clientId": 9001 }, "searchRunId": 42, "companies": [ … ] }
// — or a client NOT in the catalog at all (no linkedCompanyId):
{ "name": "…", "client": { "newClient": { "name": "Some Private Family Office" } }, "searchRunId": 42, "companies": [ … ] }
// response = ProjectDetailDto (see GET /{id})
```
`400` if name blank / neither-or-both of `clientId`|`newClient` / `linkedCompanyId` or `searchRunId`
not valid in the caller's org.

**`GET /api/app/projects?page&size`** → `Page<ProjectSummaryDto>`:
```json
{ "content": [ { "id": 7, "name": "…", "clientId": 9001,
   "clientName": "Al Rabie Saudi Foods Co.", "companyCount": 24,
   "status": "active", "createdAt": "…" } ], "totalElements": 5, "totalPages": 1 }
```

**`GET /api/app/projects/{id}`** → `ProjectDetailDto` — `client` is the `app_clients` record, with the
linked catalog company inlined when present (`linkedCompany` is `null` for an unlinked client):
```json
{ "id": 7, "name": "…", "status": "active", "searchRunId": 42,
  "client": { "id": 9001, "name": "Al Rabie Saudi Foods Co.", "linkedCompanyId": 501,
    "linkedCompany": { "id": 501, "name": "Al Rabie Saudi Foods Co.", "primaryIndustry": "FMCG", "hqCountry": "SA" } },
  "companies": [ { "id": 100, "companyId": 12, "name": "Almarai", "primaryIndustry": "FMCG",
    "hqCountry": "SA", "revenueUsd": 4200000000, "relevanceType": "Direct",
    "confidence": 91, "status": "untriaged", "mapX": 52, "mapY": 50 } ] }
```
`404` if project not in caller's org.

**`GET /api/app/clients?q`** (org's own clients) / **`POST /api/app/clients`** `{name, linkedCompanyId?}`
(201) / **`GET /api/app/clients/{id}`** → `ClientDto` (404 if not in org).

## UI — what to do this phase

Add **Confirm universe** (create a project with a client) and the **Projects** list/switcher
grouped by client.

**Tasks**
- API client `src/lib/api/appProjects.ts`: `useCreateProject()`, `useProjects()`, `useProject(id)`;
  `src/lib/api/appClients.ts`: `useClients(q)`, `useCreateClient()`.
- Confirm-universe flow (`src/features/universe/`): a "Confirm universe" action that collects the
  selected company ids (+ their relevance/confidence and any map positions) and the chosen client,
  then POSTs `/api/app/projects` with the `searchRunId` from phase 02; on success, open the project.
- **Client picker (changed):** search the **org's own clients** first via `GET /api/app/clients?q`
  ("have we worked with them before"). If none fits, create one — either by linking a catalog company
  (`newClient.linkedCompanyId`, resolved by reusing the phase-01 `/search` with only `q`) or by typing
  a free name (unlinked client, no catalog row needed). The picker sends `client:{clientId}` or
  `client:{newClient:{…}}`, never a bare `clientCompanyId`.
- Projects surfaces (`src/features/projects/`): recent-projects grid (landing), rail popover
  switcher grouped by client, and the full projects management screen (search, sort, open).

**Design references** (`doc/claude-design/ui_kits/talent-map/`)
- `universe.jsx` — `UniverseView` `onConfirm` / `canConfirm` (lines 89, 177): the confirm gate
  (needs ≥1 approved company). `approvedIds` is the selected-company set that becomes
  `companies[]`.
- `search-wizard.jsx` — `SwStep2` (Client step): the client search/create surface. Its `SW_CLIENTS`
  are design placeholders — back it with `GET /api/app/clients?q` (org clients) + a link-to-catalog
  option; "create new" maps to `newClient`.
- `projects.jsx` — three surfaces to mirror: `RecentProjects` (landing grid, `onOpen`/`onSeeAll`),
  `ProjectsPanel` (rail popover, `groupByClient`, expandable client groups), `ProjectsScreen` (full
  table: search, sortable headers, bulk select). `data.jsx` `TM_PROJECTS` = the row shape
  (`name`, `clientId`, `companies` count, `active`). `getClientName(clientId)` shows the
  client→project grouping the API's `clientName` feeds.

## Test / verify

- `JAVA_HOME=…jdk-21… mvn test -Dtest=AppProjectServiceTest,AppClientServiceTest` — mock the repos
  (Mockito; H2 can't build these tables — see README "Testing limitation"):
  - `AppClientServiceTest`: `resolveOrCreate` with an existing `clientId` (loads, org-scoped, 404
    cross-org); with `newClient` + `linkedCompanyId` (validates catalog, dedupes via
    `findByOrgIdAndLinkedCompanyId`); with `newClient` name only (unlinked); blank name → 400; both
    `clientId`+`newClient` or neither → 400; unknown `linkedCompanyId` → 400.
  - `AppProjectServiceTest`: create with 2 companies → `detail` returns client (with inlined linked
    company when set) + 2 companies; `list` shows `companyCount=2` + `clientName`; second org → empty
    `list`, 404 on `get`; blank name → 400; invalid run → 400.
- Manual (dev): `POST /api/app/clients {"name":"Al Rabie…","linkedCompanyId":501}` → create run (phase
  02) → `POST /api/app/projects` with `client:{clientId:…}` (or inline `newClient`) → `GET
  /api/app/projects` and `/{id}` (client inlined, universe listed).
- UI: confirm a universe → project appears in Projects grouped by client; open loads detail.

## Done when

- [ ] `AppClient`/`AppProject`/`AppProjectCompany` entities + repos + DTOs + services (transactional
      create) + controllers created; org-scoped.
- [ ] Client is resolved via `app_clients` (`clientId` reuse OR `newClient` create, optional catalog
      link); project references `client_id`, never `app_companies` directly.
- [ ] `AppProjectServiceTest` + `AppClientServiceTest` green (org isolation, 400s, dedupe, cascade)
      with repos mocked.
- [ ] Manual create → list → detail round-trips with client + companies.
- [ ] UI confirm-universe + client picker (org clients first) + Projects list wired.
- [ ] No existing backend file changed.
