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
  - Output shape `SearchCriteria(industry[], country[], revenueRange[], employeeRange[])` — the
    **query-active** keys phase-01's `/search` accepts. (Live probe of the real values recorded in
    memory `app-companies-real-vocab`.)
- **Combined `parsed_criteria` jsonb — one object, active + future keys together.** The wizard (design
  `search-wizard.jsx`, 6 steps) also captures **position & experience** (step 4). Those are stored but
  **NOT used to filter** — employee/executive data is not in `app_companies` yet (future). So
  `parsed_criteria` = one jsonb:
  ```
  { industry[], country[], revenueRange[], employeeRange[],   // query-active → phase-01 /search
    positions[], seniority[], experience[] }                  // captured, STORED ONLY, ignored by search
  ```
  Phase-01 `/search` reads **only the first four**. The people keys are persisted + echoed back so the
  UI round-trips them, and so they're ready when employee data lands. No exec tables, no exec search.
- **Client edits win over the LLM.** The wizard lets the user correct the AI's chips (industry/
  location/revenue) and fill position/experience before submit. So the client sends its criteria and
  the server does **not** blindly overwrite them: for each field, **use the client's value if present,
  else fall back to the LLM parse**. (Raw-only queries — no client criteria — still get a pure LLM
  parse.)
- **DTOs** — `CreateSearchRunRequest(query, mode, criteria?)` where `criteria` is the optional
  wizard-captured object (same shape as `parsed_criteria` above; may be partial or absent).
  `SearchRunDto(id, query, mode, parsedCriteria, resultCount, status, createdAt)` — `parsedCriteria`
  is the merged result. Optional `PATCH .../search-runs/{id}` to write back `resultCount` once
  phase-01 has run — see contract below.
- **`service/AppSearchRunService`** — `create`: set `org_id`/`created_by` from the principal, 400 if
  `query` blank, call `AppSearchIntentService.parse(query, mode)` for the LLM guess, **merge the
  client's `criteria` over it (client wins per field)**, store the merged `parsed_criteria` (incl. the
  people keys verbatim — people keys are never LLM-derived), persist, return DTO. `get`:
  `findByIdAndOrgId` → 404. `setResultCount(id, count, user)`: org-scoped update of `result_count`
  after the search resolves.
- **`controller/AppSearchRunController`** — `POST /api/app/search-runs`,
  `GET /api/app/search-runs/{id}`, and the small result-count write-back.

`mode` is a free string (`Search` | `Import a list` | `From brief`); no enum. For `Import a list` the
query may be empty and criteria come from the list — the LLM parse is skipped for that mode.

## Endpoint contracts

**`POST /api/app/search-runs`** (201) — LLM parses `query`; client `criteria` (if sent) is merged over
the parse (client wins per field):
```json
// request  — query is required; criteria is OPTIONAL (the wizard-captured/edited chips).
//            Omit criteria for a pure LLM parse.
{ "query": "Top FMCG distributors in the UAE doing $1–5 billion", "mode": "Search",
  "criteria": {                       // optional; any subset
    "industry": ["FMCG"], "country": ["AE"],
    "positions": ["CFO"], "seniority": ["C-Suite"], "experience": ["15+ years"]
  } }
// response  (parsedCriteria = LLM parse merged with client criteria, mapped to REAL app_companies values)
//   country → ISO-2 code (AE);  revenueRange → exact band string (1B-5B);
//   industry → free term, ILIKE-matched by phase-01 (not an enum);
//   positions/seniority/experience → STORED, echoed back, NOT used to filter (no employee data yet)
{ "id": 42, "query": "…", "mode": "Search",
  "parsedCriteria": { "industry": ["FMCG", "Food and Beverage"], "country": ["AE"],
                      "revenueRange": ["1B-5B"], "employeeRange": [],
                      "positions": ["CFO"], "seniority": ["C-Suite"], "experience": ["15+ years"] },
  "resultCount": null, "status": "active", "createdAt": "2026-07-08T10:11:12" }
```
`400` if `query` blank (except `Import a list` mode). On LLM timeout the four query-active arrays come
back empty (fail-open) — but any **client-supplied** criteria are still kept (merge), and the people
keys are always echoed as sent. The run still persists.

> **Validation scope:** only the four query-active fields are vocab-validated (`country`→ISO-2 code,
> `revenueRange`/`employeeRange`→exact band, via `AppSearchVocab`; industry stays free-text). The
> people keys (`positions`/`seniority`/`experience`) are **not** validated against any vocabulary —
> they're stored as given and never reach SQL, so there's no injection surface. (When employee data
> lands and they become filterable, add validation then.)

**`PATCH /api/app/search-runs/{id}`** (write back the count after phase-01 search runs):
```json
{ "resultCount": 24 }   // → 200, updated SearchRunDto; 404 if run not in caller's org
```

**`GET /api/app/search-runs/{id}`** → `SearchRunDto` (404 if not in caller's org).

## UI — what to do this phase

The **6-step Search Map Wizard** (`search-wizard.jsx`) is the entry. Step 1 = free-text prompt; the
backend LLM parses it and the wizard **pre-fills** the later steps' chips; the user edits, then submits
**both** the query and the (edited) criteria.

**6 steps → backend mapping** (`SW_STEPS` in `search-wizard.jsx`):
1. **Describe search** → `query`; POST triggers the LLM parse.
2. **Client** → *not this phase* — becomes `clientCompanyId` at project-create (**phase 03**).
3. **Company** (industry / revenue / employees) → `criteria.industry/revenueRange/employeeRange` — query-active.
4. **Position & experience** → `criteria.positions/seniority/experience` — **captured & stored, NOT searched** (no employee data yet).
5. **Location** → `criteria.country` (ISO-2) — query-active.
6. **Criteria** (free tags) → folded into `criteria.industry` free terms (or kept as-is on the run) — not a hard filter this phase.

**Entry: new user vs returning user (UI routing only — same endpoint).**
`isNewUser = projects.length === 0` (`app.jsx`). New user → `SearchWizardPage` (full-page onboarding);
returning user → `Landing` with `RecentProjects` + a "New search" `SearchWizardModal`. **No backend
branch** — "has projects?" is just `GET /api/app/projects` empty-vs-not (phase 03). Both paths call the
same `POST /api/app/search-runs`.

**Tasks**
- API client (`src/lib/api/appSearchRuns.ts` or fold into `appProjects.ts`): `useCreateSearchRun()`
  mutation (`{query, mode, criteria?}`) and a small `useSetSearchRunCount(id)`; types in
  `src/lib/api/types.ts` (criteria object incl. the people keys).
- Step 1 submit: POST `{query, mode}` (no criteria yet) → get `runId` + `parsedCriteria`; use it to
  **pre-fill** steps 3–6 chips. On final submit, POST again (or PATCH) with the edited `criteria`, OR
  post once at the end with `{query, mode, criteria}` — pick one in impl; the merge (client wins) makes
  both safe.
- Run the phase-01 `/search` with the four query-active fields of `parsedCriteria`; then PATCH the run
  with the result count. **Ignore the people keys when calling `/search`.**
- Show `parsedCriteria` as **editable scope chips** — correcting a chip re-runs `/search` (no re-hit of
  the LLM).
- Store the returned run `id` in the universe store (`src/lib/store/`) so the confirmed universe is
  tied to a run (consumed in phase 03's create-project call).

**Deferred to a later iteration (NOT this phase):** *AI adjacent-industry suggestions* — the AI
proposing extra selectable "adjacent" industry chips beyond the main pick. That needs an adjacency
source matching the ~523 real `primary_industry` labels (LLM free-guess vs curated vs embedding map),
which overlaps the phase-06 pgvector work. This phase only pre-fills the **direct** picks the LLM
extracts; no "AI also suggested…" row yet.

**Design references** (`doc/claude-design/ui_kits/talent-map/`)
- `search-wizard.jsx` — `SW_STEPS` (6 steps), `SearchWizardPage` (new user) / `SearchWizardModal`
  (returning), `swParsePrompt` (the **simulated** client parser the design ships — **replace with the
  real `POST /api/app/search-runs` LLM parse**). `SwStep1`…`SwStep6` are the per-step edit surfaces.
- `universe.jsx` — `UniverseView`: `summaryChips` / `buildCardCriteria` (lines 22–63) render the
  scope chips — those now display the **LLM-parsed** `parsedCriteria` (backend-supplied), not
  client-derived. `defaultCriteriaFor(query)` in the design becomes the server's job. `onConfirm` /
  `onSaveDraft` (line 89) are the confirm/draft actions the run underpins.
- `sourcing-criteria.jsx` — the manual criteria-edit surface for correcting the LLM's guess.

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
- [ ] `parsedCriteria` jsonb round-trips (incl. the people keys `positions/seniority/experience`);
      client `criteria` merges over the LLM parse (client wins per field); `resultCount` write-back works.
- [ ] People keys are stored + echoed but never passed to phase-01 `/search`.
- [ ] `AppSearchRunServiceTest` + `AppSearchIntentServiceTest` green (org isolation, 400, fail-open,
      off-vocab drop, client-merge) with the LLM stubbed.
- [ ] UI: query → run (LLM criteria) → phase-01 search → count write-back; chips editable.
- [ ] No existing backend file changed (LLM gateway is reused, not modified).

## Decided (this round)

- **One combined `parsed_criteria` jsonb** holds both the query-active fields
  (`industry/country/revenueRange/employeeRange`) and the future/people fields
  (`positions/seniority/experience`). Search reads only the query-active four; people keys are
  stored-only until employee data exists.
- **Position & experience** (wizard step 4) are **captured from the UI and resent**, persisted on the
  run, but **not used in the intent parse or the SQL search** — no employee data yet.
- **AI adjacent-industry suggestions are DEFERRED** to a later iteration (see UI section). This phase
  only pre-fills the direct picks the LLM extracts.

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
