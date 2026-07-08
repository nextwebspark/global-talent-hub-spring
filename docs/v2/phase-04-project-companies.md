# Phase 04 — Project universe list + per-company edit (map/table interactions)

## Goal

Inside an open project workspace: list the project's universe (paginated, with per-company mapping
state) and edit a single company's state — triage `status`, relevance, and map position. Backs the
map-view bubble select/reposition and the table triage controls. `status` (`untriaged | in_universe
| shortlisted | declined`, default `untriaged`) drives the sidebar buckets (In universe /
Shortlisted / Declined; untriaged rows sit in the main list awaiting a decision).

## Depends on

Phase 03 (`app_projects`, `app_project_companies`, project detail).

## What to build (backend)

- **`AppProjectCompanyRepository`** — add a paginated finder
  `findByProjectIdAndOrgId(projectId, orgId, Pageable)` (reuse the single-row
  `findByProjectIdAndCompanyIdAndOrgId` from phase 03 for the patch).
- **DTO** — `PatchProjectCompanyRequest(status?, relevanceType?, mapX?, mapY?)` — all optional; only
  provided fields are updated (partial patch). `status` ∈ `untriaged | in_universe | shortlisted |
  declined` (validate against the allowed set → 400 on unknown value); it drives the sidebar triage
  buckets. New rows start `untriaged` (from phase 03) — a PATCH moves them into a bucket.
- **`service/AppProjectService`** — add:
  - `listCompanies(projectId, user, Pageable)` — assert project in org (404 else), page the join
    rows, join each to `AppCompany` for display fields → `Page<ProjectCompanyDto>` (reuse the DTO
    from phase 03).
  - `patchCompany(projectId, companyId, req, user)` — assert project in org; load the join row via
    `findByProjectIdAndCompanyIdAndOrgId` (404 else); apply provided fields; save; return updated
    `ProjectCompanyDto`.
- **`controller/AppProjectController`** — add `GET /api/app/projects/{id}/companies?page&size` and
  `PATCH /api/app/projects/{id}/companies/{companyId}`.

Guard both by project-in-org (like `OrgGuardService`), never trust `projectId` from the path alone.

## Endpoint contracts

**`GET /api/app/projects/{id}/companies?page&size`** → `Page<ProjectCompanyDto>`:
```json
{ "content": [ { "id": 100, "companyId": 12, "name": "Almarai", "primaryIndustry": "FMCG",
    "hqCountry": "Saudi Arabia", "revenueUsd": 4200000000, "relevanceType": "Direct",
    "confidence": 91, "status": "untriaged", "mapX": 52, "mapY": 50 } ],
  "totalElements": 24, "totalPages": 1 }
```

**`PATCH /api/app/projects/{id}/companies/{companyId}`** (partial):
```json
// request (any subset)
{ "status": "shortlisted" }        // untriaged | in_universe | shortlisted | declined
{ "relevanceType": "Adjacent" }
{ "mapX": 61.5, "mapY": 42.0 }
// response = updated ProjectCompanyDto
```
`404` if project not in org or company not in that project.

## UI — what to do this phase

Build the project workspace views over the two endpoints: a table (in/out toggle) and a stylized
map (select bubble + reposition).

**Tasks**
- API client `src/lib/api/appProjects.ts`: `useProjectCompanies(projectId, params)` (paged) +
  `usePatchProjectCompany(projectId)` mutation (optimistic update, invalidate on settle).
- Table view (`src/features/universe/`): dense company grid; rows start `untriaged`, per-row triage
  control (In universe / Shortlist / Decline) → PATCH `status`; sortable columns; relevance
  shown/edited → PATCH `relevanceType`. Sidebar shows counts per `status` bucket (untriaged rows
  stay in the main list).
- Map view: render companies as bubbles positioned by `mapX/mapY`, sized by a revenue/employees
  metric; select a bubble to open the right detail panel; drag a bubble to reposition → PATCH
  `mapX/mapY`.
- Right detail panel: show the selected company's fields + a revenue/employees scaling toggle.

**Design references** (`doc/claude-design/ui_kits/talent-map/`)
- `tableview.jsx` — `TableView`: the dense grid; `Toggle` (row triage → PATCH `status`), `SortIc`
  (sortable headers), `BulkBar`/`RowContextMenu` (bulk + row actions — Decline maps to
  `status="declined"`, Shortlist to `status="shortlisted"`).
- `mapview.jsx` — `MapView` + `CompanyNode` + `radiusFor(c, metric)`: bubbles positioned by x/y %
  (= `mapX/mapY`), sized by metric; `onSelectCompany`. `startDrag` (line 42) shows the
  drag-to-reposition interaction that becomes the `mapX/mapY` PATCH. (Executive satellite pills in
  `SatelliteCluster` are out of scope this round.)
- `panel.jsx` — `RightPanel({ company, scalingMetric, onMetric, onClose, ... })`: the selected-
  company detail panel + revenue/employees metric toggle (lines 27–32). Map its fields to
  `ProjectCompanyDto`/`AppCompanyDto`.

## Test / verify

- `JAVA_HOME=…jdk-21… mvn test -Dtest=AppProjectCompanyTest` — seed a project + 3 join rows;
  `listCompanies` pages correctly; `patchCompany` with `{status:"declined"}` persists and re-reads;
  invalid `status` → 400; partial patch leaves other fields untouched; patch on a company not in the
  project → 404; patch as another org → 404; `UNIQUE(project_id, company_id)` still holds.
- Manual: `GET /api/app/projects/{id}/companies`; `PATCH .../companies/{companyId}` with
  `{"status":"shortlisted"}` then `{"mapX":61.5,"mapY":42}` → re-GET shows changes.
- UI: triage a row / drag a bubble → change persists after reload; sidebar bucket counts update.

## Done when

- [ ] Paged list + partial-patch endpoints exist; project-in-org guarded.
- [ ] `AppProjectCompanyTest` green (paging, partial patch, 404s, org isolation).
- [ ] Manual patch round-trips (status triage + map position).
- [ ] UI table triage + map reposition wired; sidebar bucket counts reflect `status`.
- [ ] No existing backend file changed.
