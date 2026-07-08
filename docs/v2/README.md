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
| 00 | `phase-00-foundation.md` | `app_*` schema DDL + `AppCompany` read entity + `GET /api/app/companies/{id}` | ☐ not started |
| 01 | `phase-01-company-search.md` | SQL filter search + facets over `app_companies` | ☐ not started |
| 02 | `phase-02-search-runs.md` | Persist a search execution (`app_search_runs`) | ☐ not started |
| 03 | `phase-03-projects.md` | Confirm universe → project under a client (`app_projects` + join) | ☐ not started |
| 04 | `phase-04-project-companies.md` | Project universe list + per-company edit (select / map position) | ☐ not started |
| 05 | `phase-05-outdated-comment-pass.md` | Mark superseded existing methods `// OUTDATED:` | ☐ not started |
| 06 | `phase-06-pgvector-later.md` | (deferred) semantic search via pgvector | ☐ deferred |

Update the Status column as phases land (☐ → ◐ in progress → ☑ done).

## Confirmed decisions

- New `app_*` tables, **same Postgres**, FK to `app_companies.id`.
- Phase-1 search = **plain SQL filters** on `app_companies`; **pgvector is a later phase**.
- New **`/api/app/*` REST**, paginated JSON, **no SSE** in early phases.
- Superseded existing code: **comment only** (`// OUTDATED: …`), never edit/delete.
- `app_companies` is a **shared master catalog** — reads are NOT org-scoped. Only the new writable
  `app_*` tables carry `org_id` and are org-scoped.

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

- `app_search_runs` — one search execution (query + resolved criteria + result count + status).
- `app_projects` — the "search map" workspace; belongs to a client company
  (`client_company_id → app_companies.id`), originates from a run (`search_run_id → app_search_runs.id`).
- `app_project_companies` — join of a project's universe to master companies + per-company mapping
  state (`status` triage ∈ `untriaged|in_universe|shortlisted|declined`, default `untriaged`;
  relevance; map x/y). `UNIQUE(project_id, company_id)`. Sidebar buckets (In universe / Shortlisted
  / Declined) = `GROUP BY status`; new rows start `untriaged` (in the list, not yet bucketed).

## New API surface (all `/api/app/*`, JSON; master reads not org-scoped)

```
GET   /api/app/companies/search   ?q&industry[]&country[]&revenueRange[]&employeeRange[]&sort&page&size
GET   /api/app/companies/facets   (same filter params → grouped counts)
GET   /api/app/companies/{id}
POST  /api/app/search-runs
GET   /api/app/search-runs/{id}
POST  /api/app/projects
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
landing search ──► GET /api/app/companies/search + /facets        (phase 01, over app_companies)
      │
      ▼ user confirms universe
POST /api/app/search-runs  ──► app_search_runs row                (phase 02)
      │
      ▼ pick client + selected companies
POST /api/app/projects     ──► app_projects + app_project_companies (phase 03)
      │
      ▼ open project workspace
GET  /api/app/projects/{id}/companies                              (phase 04)
PATCH .../companies/{companyId}  (status triage / relevance / map x,y)    (phase 04)
```
