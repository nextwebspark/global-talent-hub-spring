# Phase 02 — Search run + LLM intent extraction (`app_search_runs`)

## Goal

Turn a raw natural-language query into structured filter criteria using the LLM (GCP Vertex AI),
and record each search as an `app_search_runs` row (query + mode + LLM-parsed criteria + result
count + status), org-scoped. This is where **intent extraction** lives: `POST /api/app/search-runs`
takes the user's text, calls Vertex AI to resolve `{industry, country, revenueRange, employeeRange}`,
persists it as `parsed_criteria`, and returns those criteria so the UI can run the phase-01 SQL
search and show the scope chips. Gives every confirmed universe a durable, explainable origin the
project (phase 03) links to.

## Depends on

- Phase 00 (`app_search_runs` table exists).
- Phase 01 (search endpoint exists; the returned `parsed_criteria` is fed into its filters, and its
  result count is written back onto the run).

## Backend conventions (repeat — self-contained)

Spring Boot 3.x / Java 21 / Maven (no wrapper) / JPA / Postgres (H2 in `test`) / Lombok /
hypersistence-utils. `ddl-auto=none` — schema is external (table from phase-00 `docs/05_app_portal.sql`).
Layering Controller→Service→Repository→Entity, root pkg `com.globaltalenthub`. `@RestController
@RequiredArgsConstructor`; endpoints take `@AuthenticationPrincipal AuthenticatedUser user` →
`user.orgId()` / `user.userId()`. `jsonb` column → `@Type(JsonBinaryType.class)` (copy the mapping
from `entity/SearchQuery.java`). Org-scoped finder `findByIdAndOrgId`. Errors via
`web/GlobalExceptionHandler`. Test: `JAVA_HOME=…jdk-21… mvn test`.

## What to build (backend)

- **`entity/AppSearchRun`** — `id, org_id, created_by, query, mode, parsed_criteria (jsonb →
  `@Type(JsonBinaryType.class)`), result_count, status, created_at, updated_at`. Timestamps +
  default `status='active'` in `@PrePersist`. Copy the jsonb mapping from `entity/SearchQuery.java`.
- **`repository/AppSearchRunRepository`** — `findByIdAndOrgId(id, orgId)`.
- **`service/AppSearchIntentService`** (the LLM step) — `parse(query, mode) → SearchCriteria`:
  - Reuse the **existing** LLM gateway — do **not** build a new one. The app already wires Vertex AI
    (`config/AiConfig`, `LlmService` with `geminiPro` primary + `geminiFlash` fallback, per the
    backend CLAUDE.md). Inject `LlmService`; use the low-temp `geminiFlash` client for deterministic
    extraction.
  - **Validate against the REAL `app_companies` values, NOT `taxonomy/Taxonomy`.** `Taxonomy.java`
    belongs to the old SSE pipeline and does **not** match this table — it uses country *names*,
    different revenue cuts, and a curated 22-sector list. `app_companies` (a **GCC-only** dataset,
    54,044 rows, probed live) uses:
    - **country** = 6 **ISO-2 codes**: `AE, SA, QA, KW, OM, BH` (NOT "United Arab Emirates").
      LLM says "UAE"/"Dubai"/"Emirates" → map to `AE`. Fixed enum → exact-validate; drop unknowns.
    - **revenueRange** = 7 exact bands: `<5M, 5M-25M, 25M-100M, 100M-500M, 500M-1B, 1B-5B, 5B+`.
      Map free phrasing ("$1–5 billion") to the nearest band string(s); exact-validate; drop unknowns.
    - **employeeRange** = 8 exact bands: `1-10, 11-50, 51-200, 201-500, 501-1000, 1001-5000,
      5001-10000, 10000+` (note `501-1000`, not `501-1k`). Exact-validate; drop unknowns.
    - **industry** — `primary_industry` has **523** distinct raw LinkedIn-style labels and
      `industry_tags` **1702** — too large to enumerate/exact-validate in a prompt. **How to match
      industry is an OPEN QUESTION (see "Open questions" below) — not yet locked.** Working
      assumption for now: LLM emits free industry terms, phase-01 matches via **ILIKE on
      `primary_industry` / `search_text`** (substring), not equality — but confirm the approach
      (free-text vs top-N constrain vs fuzzy-map) before writing the prompt.
  - Put the small fixed enums (countries + the two band lists) in a new
    **`taxonomy/AppSearchVocab`** constant (derived from the table, values above) — do not reuse
    `Taxonomy`. Country/revenue/employee are exact-validated against `AppSearchVocab`; anything
    off-list is dropped (prompt-injection guard — only validated enums + ILIKE-escaped industry
    strings reach phase-01, never raw LLM text into SQL structure).
  - **Fail-open**: bound the call by the existing `app.llm.call-timeout-ms`; on timeout/parse-failure
    return **empty criteria** (search still runs unfiltered on `q`) — mirror the old pipeline's
    fail-open behavior. Never hard-fail the request on the LLM.
  - Output shape `SearchCriteria(industry[], country[], revenueRange[], employeeRange[])` — the same
    keys phase-01's `/search` accepts. (Live probe of the real values recorded in memory
    `app-companies-real-vocab`.)
- **DTOs** — `CreateSearchRunRequest(query, mode)` (raw text only — the client no longer sends
  criteria); `SearchRunDto(id, query, mode, parsedCriteria, resultCount, status, createdAt)`.
  Optional `PATCH .../search-runs/{id}` (or fold into create) to write back `resultCount` once
  phase-01 has run — see contract below.
- **`service/AppSearchRunService`** — `create`: set `org_id`/`created_by` from the principal, 400 if
  `query` blank, call `AppSearchIntentService.parse` → store `parsed_criteria`, persist, return DTO.
  `get`: `findByIdAndOrgId` → 404. `setResultCount(id, count, user)`: org-scoped update of
  `result_count` after the search resolves.
- **`controller/AppSearchRunController`** — `POST /api/app/search-runs`,
  `GET /api/app/search-runs/{id}`, and the small result-count write-back.

`mode` is a free string (`Search` | `Import a list` | `From brief`); no enum. For `Import a list` the
query may be empty and criteria come from the list — the LLM parse is skipped for that mode.

## Endpoint contracts

**`POST /api/app/search-runs`** (201) — LLM parses `query` → `parsedCriteria`:
```json
// request  (raw text only; NO client-supplied criteria)
{ "query": "Top FMCG distributors in the UAE doing $1–5 billion", "mode": "Search" }
// response  (parsedCriteria produced by Vertex AI, mapped to REAL app_companies values)
//   country → ISO-2 code (AE);  revenueRange → exact band string (1B-5B);
//   industry → free term, ILIKE-matched by phase-01 (not an enum)
{ "id": 42, "query": "…", "mode": "Search",
  "parsedCriteria": { "industry": ["FMCG", "Food and Beverage"], "country": ["AE"],
                      "revenueRange": ["1B-5B"], "employeeRange": [] },
  "resultCount": null, "status": "active", "createdAt": "2026-07-08T10:11:12" }
```
`400` if `query` blank (except `Import a list` mode). On LLM timeout, `parsedCriteria` comes back with
empty arrays (fail-open) and the run still persists.

**`PATCH /api/app/search-runs/{id}`** (write back the count after phase-01 search runs):
```json
{ "resultCount": 24 }   // → 200, updated SearchRunDto; 404 if run not in caller's org
```

**`GET /api/app/search-runs/{id}`** → `SearchRunDto` (404 if not in caller's org).

## UI — what to do this phase

The user types only a query; the **backend** turns it into criteria. Persist the run first, use the
returned criteria to drive the phase-01 search, then write the result count back.

**Tasks**
- API client (`src/lib/api/appSearchRuns.ts` or fold into `appProjects.ts`): `useCreateSearchRun()`
  mutation (query + mode) and a small `useSetSearchRunCount(id)`; types in `src/lib/api/types.ts`.
- On "Discover Companies" (phase-01 landing): POST `{query, mode}` → get back `runId` +
  `parsedCriteria`; run the phase-01 `/search` with those criteria; then PATCH the run with the
  result count.
- Show the `parsedCriteria` as **editable scope chips** — the LLM guess is a starting point the user
  can correct before/after searching (correcting a chip re-runs `/search`; it does not need to
  re-hit the LLM).
- Store the returned run `id` in the universe store (`src/lib/store/`) so the confirmed universe is
  tied to a run (consumed in phase 03's create-project call).

**Design references** (`doc/claude-design/ui_kits/talent-map/`)
- `universe.jsx` — `UniverseView`: `summaryChips` / `buildCardCriteria` (lines 22–63) render the
  scope chips — those now display the **LLM-parsed** `parsedCriteria` (backend-supplied), not
  client-derived. `defaultCriteriaFor(query)` in the design becomes the server's job. `onConfirm` /
  `onSaveDraft` (line 89) are the confirm/draft actions the run underpins.
- `search-wizard.jsx` / `sourcing-criteria.jsx` — the manual criteria-edit surface for correcting the
  LLM's guess.

## Test / verify

- `JAVA_HOME=…jdk-21… mvn test -Dtest=AppSearchRunServiceTest` — in the `test` profile the LLM is
  mocked (Vertex autoconfig excluded, per CLAUDE.md), so **stub `LlmService`/`AppSearchIntentService`**:
  - create run for org A → `get` by org A returns it with `parsedCriteria` round-tripped (jsonb);
    `get` as org B → 404; blank query → 400; `PATCH resultCount` persists and is org-scoped.
  - `AppSearchIntentServiceTest` — feed a canned LLM response → country name "United Arab Emirates"
    maps to `AE`; a band like `"$1-5B"`/`"1B-5B"` validates to the exact `1B-5B` string; an
    off-vocab country/band (e.g. `"US"`, `"$10-50M"`) is **dropped**; industry free terms pass
    through un-validated (ILIKE-matched downstream); simulate LLM timeout → returns empty criteria
    (fail-open), no exception.
- Manual (dev, real Vertex creds): `POST /api/app/search-runs {"query":"pharma distributors in Saudi
  Arabia","mode":"Search"}` → `parsedCriteria.country` = `["SA"]`, industry populated; `GET` it back.

## Done when

- [ ] `AppSearchRun` entity/repo/DTOs/service/controller created; org-scoped.
- [ ] `AppSearchIntentService` calls the existing `LlmService` (geminiFlash); maps country→ISO-2 +
      revenue/employee→exact `app_companies` bands via new `taxonomy/AppSearchVocab` (NOT the old
      `Taxonomy`); industry left free for ILIKE; **fail-open** on timeout — no new LLM gateway added.
- [ ] `parsedCriteria` jsonb round-trips; `resultCount` write-back works.
- [ ] `AppSearchRunServiceTest` + `AppSearchIntentServiceTest` green (org isolation, 400, fail-open,
      off-vocab drop) with the LLM stubbed.
- [ ] UI: query → run (LLM criteria) → phase-01 search → count write-back; chips editable.
- [ ] No existing backend file changed (LLM gateway is reused, not modified).

## Open questions (resolve at implementation)

- **Industry matching (UNRESOLVED — decide before building the intent prompt).** `primary_industry`
  has **523** distinct labels + `industry_tags` **1702** — too many to exact-validate or list fully
  in a prompt. Three candidate approaches, not yet chosen:
  1. **Free-text → ILIKE** (docs currently assume this): LLM emits raw terms; phase-01 does
     `primary_industry ILIKE %term%`. Simplest; risk of over/under-match on vague terms.
  2. **Constrain to top-N**: inject the top ~40–60 real `primary_industry` labels; LLM picks from
     them, exact-validate like countries. Precise but misses the long tail.
  3. **Hybrid map**: LLM emits free terms, a fuzzy/embedding lookup maps them to the closest real
     labels/tags. Best recall+precision; heavier; overlaps the phase-06 pgvector work.
  Decide in the impl session; whichever is picked, keep the SQL side parameterized (bound ILIKE
  param or validated `IN`) — never concatenate LLM text into SQL.
- **Exact prompt text + few-shot examples** for the intent LLM call — draft during impl, then pin the
  final prompt in the PR. Must include the fixed country/revenue/employee vocab (from
  `AppSearchVocab`) and delimit the untrusted user query.
- **Country display**: DB stores ISO-2 (`AE`); decide whether the API returns codes and the UI maps
  to friendly names, or the DTO adds a display name. (Filtering always uses the code.)
