# Integration Testing & Live-Credential Verification

All 119 unit tests run with **H2 + Mockito** — no live credentials needed:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test
```

> **Build note:** Homebrew `mvn` bundles JDK 26, which breaks Lombok 1.18.34
> (annotation processor no-ops → "cannot find symbol getX()"). Always build with
> JDK 21. The project targets Java 21.

## What unit tests cover (no creds)

| Area | Tests |
|---|---|
| Scoring (`CompanyScore`) | pure-function parity with `companyScore.ts` |
| Enrichment filter (`EnrichmentFilterService`) | vocab validation, unmapped detection, LLM mocked |
| Seed query (`CompanyEnrichmentQueryService`) | OR-candidate-pool + Java scoring/sort (repo mocked) |
| Non-destructive upsert (`CompanyService`) | 4 invariants incl. confidence overwrite + provenance |
| SSE pipeline (`SearchPipelineService` + MockMvc) | full event sequence, unmapped path |
| LLM (`LlmService`) | pro→flash fallback |
| Brief extract/summary | PDF/DOCX/TXT, fail-open |
| All CRUD controllers/services | org-scoping, validation, 404/403/409 |
| Dashboard analytics | revenue bands, concentration, USD remuneration medians |
| Clockwork client | configuration guard |

## Steps requiring LIVE credentials (run manually after creds are set)

Set in environment or `application.yml`:

| Variable | For |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://host:5432/db?sslmode=require` |
| `SUPABASE_JWT_SECRET` | HS256 token validation |
| `GOOGLE_CLOUD_PROJECT` (+ Vertex creds) or `GOOGLE_API_KEY` | Gemini |
| `MAPBOX_ACCESS_TOKEN` | `/api/config` |
| `CLOCKWORK_API_KEY/_SECRET/_FIRM_KEY/_FIRM_SLUG` | Clockwork endpoints |

### 1. The one query that needs real Postgres (NOT H2)

`CompanyEnrichmentRepository.queryCandidatePool` uses Postgres-native array operators
(`= ANY(...)`, `sub_tags && ...`, `text[]` casts) that **do not run on H2**. Verify against
a real DB (or Testcontainers):

```sql
-- Confirm the live company_enrichment table carries these columns directly
-- (matches docs/supabase-schema/company_enrichment.sql, NOT the stale db-extent/ version):
\d company_enrichment   -- expect company_name, slug, country, company_pk (nullable)
```

Then hit `GET /api/search/enhanced-stream?query=banks+in+uae&sessionId=test&access_token=<jwt>`
and confirm SSE events stream (`search_created` → `intent_extracted` → `company_found*`
→ `company_enriched*` → `search_complete` → `done`).

### 2. End-to-end checklist (live React frontend → Spring :5000)

- [ ] `GET /api/health` → 200
- [ ] `GET /api/auth/me` with Supabase JWT → user + org + profile
- [ ] `GET /api/companies` → org-scoped list (not other org's data)
- [ ] SSE stream returns enriched companies
- [ ] `POST /api/search/upload-brief` (PDF) → extracted text
- [ ] `POST /api/search/add-to-project` → companies linked
- [ ] `GET /api/dashboard/{id}` → analytics incl. remuneration stats
- [ ] `POST /api/import-project` → bulk import, companies deduped
- [ ] Non-destructive: re-run pipeline on a company with an existing sector → sector unchanged
- [ ] Non-destructive: `manuallyEditedFields` NOT overwritten by enrichment

## Known follow-ups (require live keys + further porting)

- **Deep Clockwork candidate matching** (`/api/enrichment/match`, `/confirm`,
  `/create-from-clockwork`) — surfaced as `501 NOT_IMPLEMENTED`. Needs the full
  `clockworkEnrichment/orchestrate.ts` port + live Clockwork.
- **Web-search revenue/executive enrichment** (`enrichSearchResults` in `/enrich-all`) —
  the Serper/Gemini search adapter is not ported. `/enrich-all` currently does the
  LLM sector backfill + returns refreshed companies.
- **`@AuthenticationPrincipal` email** — `auth/me` returns email from the org-member
  record (the JWT email claim is not yet surfaced on the principal; add to
  `SupabaseJwtFilter` + `AuthenticatedUser` if the frontend needs the live token email).
