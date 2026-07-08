# Phase 02 — Persist a search execution (`app_search_runs`)

## Goal

Record each search the user runs as an `app_search_runs` row (query + mode + resolved filter
criteria + result count + status), org-scoped. Gives every confirmed universe a durable origin the
project (phase 03) links to.

## Depends on

- Phase 00 (`app_search_runs` table exists).
- Phase 01 (search endpoint exists; UI has the filter criteria + result count to record).

## What to build (backend)

- **`entity/AppSearchRun`** — `id, org_id, created_by, query, mode, parsed_criteria (jsonb →
  `@Type(JsonBinaryType.class)`), result_count, status, created_at, updated_at`. Timestamps +
  default `status='active'` in `@PrePersist`. Copy jsonb mapping from `entity/SearchQuery.java`.
- **`repository/AppSearchRunRepository`** — `findByIdAndOrgId(id, orgId)`.
- **DTOs** — `CreateSearchRunRequest(query, mode, parsedCriteria, resultCount)`;
  `SearchRunDto(id, query, mode, parsedCriteria, resultCount, status, createdAt)`.
- **`service/AppSearchRunService`** — `create` (set `org_id`/`created_by` from the principal,
  400 if `query` blank) and `get` (`findByIdAndOrgId` → 404).
- **`controller/AppSearchRunController`** — `POST /api/app/search-runs`,
  `GET /api/app/search-runs/{id}`.

`mode` is a free string (`Search` | `Import a list` | `From brief`); no enum.

## Endpoint contracts

**`POST /api/app/search-runs`** (201):
```json
// request
{ "query": "Top FMCG distributors in UAE", "mode": "Search",
  "parsedCriteria": { "industry": ["FMCG"], "country": ["UAE"], "revenueRange": ["$1B–5B"] },
  "resultCount": 24 }
// response
{ "id": 42, "query": "…", "mode": "Search", "parsedCriteria": {…},
  "resultCount": 24, "status": "active", "createdAt": "2026-07-08T10:11:12" }
```
`400` if `query` blank.

**`GET /api/app/search-runs/{id}`** → `SearchRunDto` (404 if not in caller's org).

## UI — what to do this phase

Persist the run when the user kicks off discovery, and hold its id for phase 03.

**Tasks**
- API client: add `useCreateSearchRun()` mutation (`src/lib/api/appSearchRuns.ts` or fold into
  `appProjects.ts`); types in `src/lib/api/types.ts`.
- On "Discover Companies" (from phase-01 landing), after the search resolves, POST the query + the
  resolved filter criteria + the result count to `/api/app/search-runs`.
- Store the returned run `id` in the universe store (`src/lib/store/`) so the confirmed universe is
  tied to a run (consumed in phase 03's create-project call).

**Design references** (`doc/claude-design/ui_kits/talent-map/`)
- `universe.jsx` — `UniverseView`: the discovery container. `defaultCriteriaFor(query)` /
  `buildCardCriteria` / `summaryChips` (lines 22–63) show how the design derives + displays the
  search criteria that becomes `parsedCriteria`. The `onConfirm` / `onSaveDraft` props (line 89)
  are the confirm/draft actions the run underpins.
- `search-wizard.jsx` / `sourcing-criteria.jsx` — structured criteria capture, if the UI wants to
  send richer `parsedCriteria` than the raw query.

## Test / verify

- `JAVA_HOME=…jdk-21… mvn test -Dtest=AppSearchRunServiceTest` — create run for org A → `get` by
  org A returns it with `parsedCriteria` round-tripped; `get` as org B → 404; blank query → 400.
- Manual: `POST /api/app/search-runs` with a criteria body → 201; `GET` it back.

## Done when

- [ ] `AppSearchRun` entity/repo/DTOs/service/controller created; org-scoped.
- [ ] `parsedCriteria` jsonb round-trips.
- [ ] `AppSearchRunServiceTest` green (org isolation + 400).
- [ ] UI records a run on discovery and keeps its id.
- [ ] No existing backend file changed.
