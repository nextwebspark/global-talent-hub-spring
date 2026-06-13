# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

AI-powered executive search / talent-mapping SaaS. Spring Boot 3.3.5, Java 21, PostgreSQL/JPA, Supabase JWT auth, Vertex AI Gemini, SSE streaming. Ported from a prior Node/Express + TypeScript codebase — many classes carry `port of X.ts` comments tracing their origin.

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

`mvn test` auto-activates the `test` profile (H2 in-memory, Vertex AI autoconfig excluded). Override the port with `PORT`. Key runtime env vars: `DATABASE_URL`, `SUPABASE_JWT_SECRET`, `GOOGLE_CLOUD_PROJECT`, `CLOCKWORK_*`.

## Architecture

Layering: **Controller → Service → Repository (Spring Data JPA) → Entity**. Root package `com.globaltalenthub`.

- **Auth flow**: `SupabaseJwtFilter` validates an HS256 JWT (`SUPABASE_JWT_SECRET`), looks up the `OrgMember` by userId, and builds an `AuthenticatedUser(userId, email, orgId, orgRole)` principal. `SecurityConfig` is stateless; public routes are `/api/health`, `/api/auth/**`, `/api/config`, and SPA assets — everything else requires auth. The SSE route accepts a `?access_token=` query param, scoped to `/api/search/enhanced-stream` only.

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
- Review/testing docs at the root — `CODE_REVIEW.md`, `SECURITY_REVIEW.md`, `INTEGRATION_TESTING.md` — provide background context.
