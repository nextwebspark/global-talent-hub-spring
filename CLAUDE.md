# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

AI-powered executive search / talent-mapping SaaS. Spring Boot 3.3.5, Java 21, PostgreSQL/JPA, app-owned (self-hosted) HS256 JWT auth, Vertex AI Gemini, SSE streaming. Ported from a prior Node/Express + TypeScript codebase — many classes carry `port of X.ts` comments tracing their origin.

## Build & Test Commands

- **No Maven wrapper** (no `mvnw`) — use `mvn` directly (Maven 3.9.6).
- **JDK 21 required.** A Homebrew `mvn` may bundle JDK 26, which breaks Lombok 1.18.34. Pin `JAVA_HOME` to a JDK 21, e.g.:
  ```bash
  JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test
  ```

| Task | Command |
|------|---------|
| Build | `mvn clean package` |
| Run (dev profile, port 5000) | `mvn spring-boot:run` |
| Run prod profile | `SPRING_PROFILES_ACTIVE=prod mvn spring-boot:run` |
| Test all (H2 + mocked LLM, no live creds) | `mvn test` |
| Single test class | `mvn test -Dtest=ClassName` |
| Single test method | `mvn test -Dtest=ClassName#method` |

`mvn test` auto-activates the `test` profile (H2 in-memory, Vertex AI autoconfig excluded). Override the port with `PORT`. Key runtime env vars: `DATABASE_URL`, `DATABASE_USERNAME` / `DATABASE_PASSWORD` (Supabase pooler uses separate creds, required in prod), `APP_JWT_SECRET` (>=32 bytes; `APP_JWT_EXPIRY_SECONDS` optional, default 7d), `GOOGLE_CLOUD_PROJECT`, `CLOCKWORK_*`.

**Profiles**: `dev` (default), `prod`, `test` (auto on `mvn test`), and `local` — `application-local.properties` is gitignored and holds real DB/Vertex creds for local runs; copy from `application-local.properties.example` and activate with `SPRING_PROFILES_ACTIVE=local`. A `railway-vertex-key.json` may sit at repo root; it's covered by the `*-vertex-key.json` gitignore pattern — don't rename or move it in commits.

**Container / Cloud Run**: `Dockerfile` is multi-stage (Maven → `eclipse-temurin:21-jre`), runs as non-root user `spring`, defaults to `SPRING_PROFILES_ACTIVE=prod`, and honors Cloud Run's injected `PORT` (via `server.port=${PORT:5000}`). Image build skips tests — run them in CI/local.

## Architecture

Layering: **Controller → Service → Repository (Spring Data JPA) → Entity**. Root package `com.globaltalenthub`.

- **Auth flow**: app-owned identity (replaced Supabase Auth). `JwtService` mints/parses HS256 tokens signed with `APP_JWT_SECRET` (subject = user UUID, `email` claim); it fails fast at startup if the placeholder secret is used outside the `test` profile. `AuthService` handles signup (atomic user + org + owner membership + profile) and login — passwords are bcrypt hashed against the `User` entity (`hak_auth_users`). `JwtAuthFilter` validates the token, looks up the `OrgMember` by userId, and builds an `AuthenticatedUser(userId, email, orgId, orgRole)` principal; a valid token with no org membership is rejected (403) on org-scoped routes. `SecurityConfig` is stateless; public routes are `/api/health`, `/api/auth/**` (signup/login), `/api/config`, and SPA assets — everything else requires auth. The SSE route accepts a `?access_token=` query param, scoped to `/api/search/enhanced-stream` only.

- **Multi-tenancy**: `org_id` lives on tenant-scoped entities; repositories use `findByIdAndOrgId` finders, and `OrgGuardService` centralizes org-isolation assertions. A null orgId blocks access to org-scoped endpoints.

- **Data**: PostgreSQL in prod, H2 in test. `ddl-auto=none` — the schema is managed externally (no Flyway/Liquibase; baseline established at commit `d0741b8`). Tables are prefixed `hak_*`. JSONB and text-array columns are handled via Hypersistence Utils.

- **Search pipeline** (`service/pipeline/`): `SearchPipelineService` orchestrates the SSE event stream (`status` → `intent_extracted` → `company_found`×N → `company_enriched`×N → `search_complete`). `EnrichmentFilterService` does prompt-injection-safe NLP→vocabulary classification, validating LLM output against `taxonomy/Taxonomy` (a closed sector/band vocabulary). Runs on the `sseTaskExecutor` pool.

- **LLM gateway** (`LlmService`): two ChatClient beans — `geminiPro` (primary) and `geminiFlash` (fallback, temp=0). Every call is bounded by `app.llm.call-timeout-ms` (30s). Fail-open: classifier and brief-summary steps fall through on timeout rather than hard-failing the request.

- **Clockwork integration** (`service/clockwork/ClockworkApiClient`): REST client to Clockwork Recruiting (`Authorization: Token base64(key:secret)` + `X-API-Key`). Candidate import is idempotent by `clockworkId`. Org isolation: a linked project must belong to the caller's org.

- **Other notable services**: `ImportProjectService` (tabular Excel/paste bulk ingest → search query + companies + executives), `CompanyService.upsertNonDestructive` (per-field confidence 1–10, never overwrites manually-edited fields), `BriefExtractService` (PDF/DOCX extraction via PDFBox + POI).

- **Cross-cutting**: `web/GlobalExceptionHandler` (`@RestControllerAdvice`, sanitized error JSON, no stack-trace leakage). Config classes in `config/`: `AiConfig`, `AsyncConfig`, `SecurityConfig`, `CorsConfig`, `StaticResourceConfig`.

## Conventions

- Lombok throughout (`@Slf4j`, etc.). `lombok.config` at the root copies Spring `@Qualifier`/`@Value` annotations onto Lombok-generated constructor parameters.
- No checkstyle/spotless configured.
- Shared test-data UUIDs live in `src/test/java/com/globaltalenthub/TestIds.java`. Reuse those constants instead of redeclaring `UUID.fromString(...)` literals per test class.
- Review/testing docs at the root — `CODE_REVIEW.md`, `SECURITY_REVIEW.md`, `INTEGRATION_TESTING.md` — plus `docs/auth-jwt.md` for the app-owned JWT design, provide background context.
- Stale comment in `pom.xml` (line 110) still labels the `jjwt` deps as "Supabase HS256"; auth was rewritten to app-owned JWT — the deps stay, the comment is misleading.

## Deploy / CI-CD

Backend runs on **Cloud Run** (`gth-api`, project `hak-talent-mapping`, region `us-central1`), built from the root `Dockerfile` via Cloud Build (`gcloud run deploy --source .`). It scales to zero, authenticates to Vertex AI via the runtime service account's ADC (no key file), and reads all config from env vars / Secret Manager.

- **Runtime config**: non-secret values are passed as `--set-env-vars` (`SPRING_PROFILES_ACTIVE=prod`, `DATABASE_URL`, `DATABASE_USERNAME`, `GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLOUD_LOCATION`, `MAPBOX_ACCESS_TOKEN`, `APP_CORS_ALLOWED_ORIGINS`). Secrets come from Secret Manager via `--set-secrets`: `DATABASE_PASSWORD=gth-db-password:latest`, `APP_JWT_SECRET=gth-jwt-secret:latest`. Frontend lives on Vercel (`global-talent-hub-ui.vercel.app`); its origin must be in `APP_CORS_ALLOWED_ORIGINS`.

- **CI/CD**: `.github/workflows/deploy-prod.yml` auto-deploys prod on push to `main` — `mvn test` → Cloud Build → `gcloud run deploy`. Auth is **keyless** via Workload Identity Federation (GitHub OIDC), no SA JSON key. Three GitHub Actions repo **variables** drive it: `GCP_WIF_PROVIDER`, `GCP_DEPLOY_SA` (`github-deployer@…`), and `MAPBOX_ACCESS_TOKEN` (public `pk.*` token, kept out of source so push protection doesn't flag it). One-time setup is automated in `scripts/setup-wif.sh` (creates the deploy SA, WIF pool/provider restricted to this repo, and IAM bindings); run it once, then set the repo variables it prints.

## Behavioral Guidelines

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

NOTE : all code will be reviewed by codex latest model.
