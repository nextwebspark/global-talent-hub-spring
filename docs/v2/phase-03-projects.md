# Phase 03 — Confirm universe → project under a client

## Goal

Turn a confirmed universe into a persistent **project** ("search map"): create an `app_projects`
row (with a **client company** + originating search run) plus one `app_project_companies` join row
per selected company. Expose list + detail. This is the "confirm universe" → workspace step.

## Depends on

- Phase 00 (`app_projects`, `app_project_companies` tables).
- Phase 02 (`app_search_runs` + a run id to attach).
- Phase 01 (company ids come from search results).

## What to build (backend)

- **`entity/AppProject`** — `id, org_id, created_by, name, client_company_id (→app_companies.id),
  search_run_id (→app_search_runs.id), status, created_at, updated_at`. Timestamps + default
  `status='active'` in lifecycle hooks.
- **`entity/AppProjectCompany`** — `id, org_id, project_id, company_id, relevance_type, confidence,
  status(default 'untriaged'), map_x, map_y, created_at`. `@UniqueConstraint(project_id,
  company_id)`. `status` ∈ `untriaged | in_universe | shortlisted | declined` (triage state; String,
  not JPA enum — matches the DDL `CHECK`). New rows default `untriaged`.
- **Repositories** — `AppProjectRepository`: `findByOrgId(orgId, Pageable)`,
  `findByIdAndOrgId`. `AppProjectCompanyRepository`: `findByProjectIdAndOrgId`,
  `countByProjectIdAndOrgId`, `findByProjectIdAndCompanyIdAndOrgId`.
- **DTOs** — `CreateProjectRequest(name, clientCompanyId, searchRunId, companies[])` where each
  `ProjectCompanyInput(companyId, relevanceType, confidence, mapX, mapY)`;
  `ProjectSummaryDto(id, name, clientCompanyId, clientCompanyName, companyCount, status, createdAt)`;
  `ProjectDetailDto(id, name, status, searchRunId, client (AppCompanyDto|null), companies[])` where
  each `ProjectCompanyDto(id, companyId, name, primaryIndustry, hqCountry, revenueUsd,
  relevanceType, confidence, status, mapX, mapY)`
  (`status` ∈ `untriaged|in_universe|shortlisted|declined`; new rows default `untriaged`).
- **`service/AppProjectService`** (`@Transactional` create):
  - `create` — validate name non-blank (400), validate `clientCompanyId` exists in `app_companies`
    (400) and `searchRunId` in caller's org (400); insert project + one join row per input company;
    return detail.
  - `list` — `findByOrgId` → summaries, filling `companyCount` (count query) + `clientCompanyName`
    (lookup in `app_companies`).
  - `detail` — `findByIdAndOrgId` (404); load join rows + join each to `AppCompany` for display;
    load client `AppCompany` (nullable).
  - Inject `AppProjectRepository`, `AppProjectCompanyRepository`, `AppSearchRunRepository`,
    `AppCompanyRepository`.
- **`controller/AppProjectController`** — `POST /api/app/projects` (201),
  `GET /api/app/projects?page&size` (sort by `updatedAt` desc), `GET /api/app/projects/{id}`.

Model note (design): a project row is `{ id, name, clientId, companies(count), active }`
(`data.jsx` `TM_PROJECTS`). `execs` count is out of scope (executives skipped) — omit or `0`.
`relevance_type` ∈ `Direct | Adjacent | AI Inferred`.

## Endpoint contracts

**`POST /api/app/projects`** (201):
```json
// request
{ "name": "Top FMCG distributors in UAE", "clientCompanyId": 501, "searchRunId": 42,
  "companies": [ { "companyId": 12, "relevanceType": "Direct", "confidence": 91, "mapX": 52, "mapY": 50 },
                 { "companyId": 18, "relevanceType": "Adjacent", "confidence": 74 } ] }
// response = ProjectDetailDto (see GET /{id})
```
`400` if name blank / client or run invalid.

**`GET /api/app/projects?page&size`** → `Page<ProjectSummaryDto>`:
```json
{ "content": [ { "id": 7, "name": "…", "clientCompanyId": 501,
   "clientCompanyName": "Al Rabie Saudi Foods Co.", "companyCount": 24,
   "status": "active", "createdAt": "…" } ], "totalElements": 5, "totalPages": 1 }
```

**`GET /api/app/projects/{id}`** → `ProjectDetailDto`:
```json
{ "id": 7, "name": "…", "status": "active", "searchRunId": 42,
  "client": { "id": 501, "name": "Al Rabie Saudi Foods Co.", "primaryIndustry": "FMCG", "hqCountry": "Saudi Arabia" },
  "companies": [ { "id": 100, "companyId": 12, "name": "Almarai", "primaryIndustry": "FMCG",
    "hqCountry": "Saudi Arabia", "revenueUsd": 4200000000, "relevanceType": "Direct",
    "confidence": 91, "status": "untriaged", "mapX": 52, "mapY": 50 } ] }
```
`404` if project not in caller's org.

## UI — what to do this phase

Add **Confirm universe** (create a project with a client) and the **Projects** list/switcher
grouped by client.

**Tasks**
- API client `src/lib/api/appProjects.ts`: `useCreateProject()`, `useProjects()`, `useProject(id)`.
- Confirm-universe flow (`src/features/universe/`): a "Confirm universe" action that collects the
  selected company ids (+ their relevance/confidence and any map positions) and the client company,
  then POSTs `/api/app/projects` with the `searchRunId` from phase 02; on success, open the project.
- Client picker: choose the `clientCompanyId` (a company from `app_companies`) at confirm time.
- Projects surfaces (`src/features/projects/`): recent-projects grid (landing), rail popover
  switcher grouped by client, and the full projects management screen (search, sort, open).

**Design references** (`doc/claude-design/ui_kits/talent-map/`)
- `universe.jsx` — `UniverseView` `onConfirm` / `canConfirm` (lines 89, 177): the confirm gate
  (needs ≥1 approved company). `approvedIds` is the selected-company set that becomes
  `companies[]`.
- `projects.jsx` — three surfaces to mirror: `RecentProjects` (landing grid, `onOpen`/`onSeeAll`),
  `ProjectsPanel` (rail popover, `groupByClient`, expandable client groups), `ProjectsScreen` (full
  table: search, sortable headers, bulk select). `data.jsx` `TM_PROJECTS` = the row shape
  (`name`, `clientId`, `companies` count, `active`). `getClientName(clientId)` shows the
  client→project grouping the API's `clientCompanyName` feeds.

## Test / verify

- `JAVA_HOME=…jdk-21… mvn test -Dtest=AppProjectServiceTest` — seed a client company + a run;
  create a project with 2 companies → `detail` returns client + 2 companies; `list` shows
  `companyCount=2`; second org sees neither (empty `list`, 404 on `get`); blank name → 400; invalid
  client → 400; deleting a project cascades its join rows.
- Manual: create run (phase 02) → POST project referencing it + a `clientCompanyId` →
  `GET /api/app/projects` and `/{id}`.
- UI: confirm a universe → project appears in Projects grouped by client; open loads detail.

## Done when

- [ ] Entities/repos/DTOs/service (transactional create)/controller created; org-scoped.
- [ ] `AppProjectServiceTest` green (org isolation, 400s, cascade).
- [ ] Manual create → list → detail round-trips with client + companies.
- [ ] UI confirm-universe + Projects list wired.
- [ ] No existing backend file changed.
