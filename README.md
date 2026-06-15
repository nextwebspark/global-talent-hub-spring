# Global Talent Hub

AI-powered executive search and talent-mapping SaaS. Spring Boot backend with self-hosted JWT auth, multi-tenant PostgreSQL data model, Vertex AI Gemini integration, and SSE-streamed search pipeline.

## Tech Stack

- **Java 21** + **Spring Boot 3.3.5**
- **PostgreSQL** (prod) / **H2** (test) via Spring Data JPA
- **Hypersistence Utils** for JSONB and text-array column types
- **Vertex AI Gemini** (`geminiPro` primary, `geminiFlash` fallback) via Spring AI
- **jjwt** for HS256 JWT signing/verification
- **Lombok**, **PDFBox**, **Apache POI**
- **Maven 3.9.6** (no wrapper)

## Prerequisites

- JDK 21 (Lombok 1.18.34 breaks on JDK 26)
- Maven 3.9.6
- PostgreSQL 14+ (for non-test runs)
- Google Cloud project with Vertex AI enabled (for live LLM calls)

Pin `JAVA_HOME` if your shell's `mvn` bundles a newer JDK:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
```

## Quick Start

```bash
git clone <repo-url>
cd global-talent-hub-spring
cp src/main/resources/application-local.properties.example src/main/resources/application-local.properties
# edit application-local.properties with real DB + Vertex creds
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

App boots on `http://localhost:5000`.

## Configuration

### Profiles

| Profile | Purpose | Activation |
|---------|---------|------------|
| `dev` | Default local dev | Implicit |
| `local` | Local run with real DB/Vertex creds (gitignored) | `SPRING_PROFILES_ACTIVE=local` |
| `prod` | Cloud Run / Railway | `SPRING_PROFILES_ACTIVE=prod` |
| `test` | H2 in-memory + mocked LLM | Auto-activated by `mvn test` |

### Required Environment Variables

| Var | Description |
|-----|-------------|
| `DATABASE_URL` | JDBC URL for PostgreSQL |
| `DATABASE_USERNAME` | DB user (Supabase pooler requires separate creds in prod) |
| `DATABASE_PASSWORD` | DB password |
| `APP_JWT_SECRET` | HS256 signing key, **min 32 bytes**. App fails at startup if placeholder used outside `test` profile |
| `APP_JWT_EXPIRY_SECONDS` | Optional, default 7 days |
| `GOOGLE_CLOUD_PROJECT` | GCP project ID for Vertex AI |
| `CLOCKWORK_*` | Clockwork Recruiting API credentials |
| `PORT` | Optional, default 5000 |

Vertex AI service-account key files matching `*-vertex-key.json` are gitignored (do not rename or commit).

## Build and Test

| Task | Command |
|------|---------|
| Build JAR | `mvn clean package` |
| Run dev (port 5000) | `mvn spring-boot:run` |
| Run prod profile | `SPRING_PROFILES_ACTIVE=prod mvn spring-boot:run` |
| Run all tests | `mvn test` |
| Single test class | `mvn test -Dtest=ClassName` |
| Single test method | `mvn test -Dtest=ClassName#method` |

Tests use H2 + mocked LLM — no live credentials required.

## Docker / Cloud Run

Multi-stage `Dockerfile` (Maven build → `eclipse-temurin:21-jre`). Runs as non-root user `spring`, defaults to `SPRING_PROFILES_ACTIVE=prod`, honors Cloud Run's injected `PORT`.

```bash
docker build -t global-talent-hub .
docker run -p 5000:5000 \
  -e DATABASE_URL=... \
  -e DATABASE_USERNAME=... \
  -e DATABASE_PASSWORD=... \
  -e APP_JWT_SECRET=... \
  -e GOOGLE_CLOUD_PROJECT=... \
  global-talent-hub
```

Image build skips tests — run them in CI or locally.

## Architecture

Layering: **Controller → Service → Repository (JPA) → Entity**. Root package `com.globaltalenthub`.

- **Auth**: app-owned HS256 JWT. `JwtService` mints/parses tokens (subject = user UUID, `email` claim). `AuthService` handles signup (atomic user + org + owner membership + profile) and login with bcrypt password hashing. `JwtAuthFilter` builds an `AuthenticatedUser(userId, email, orgId, orgRole)` principal. Valid token without org membership → 403 on org-scoped routes. Public routes: `/api/health`, `/api/auth/**`, `/api/config`, SPA assets. SSE route `/api/search/enhanced-stream` accepts `?access_token=` query param.
- **Multi-tenancy**: `org_id` on tenant-scoped entities. Repositories use `findByIdAndOrgId`. `OrgGuardService` centralizes isolation checks.
- **Schema**: `ddl-auto=none` — schema managed externally (no Flyway/Liquibase, baseline at commit `d0741b8`). Tables prefixed `hak_*`.
- **Search pipeline** (`service/pipeline/`): `SearchPipelineService` orchestrates SSE event stream (`status` → `intent_extracted` → `company_found`×N → `company_enriched`×N → `search_complete`). Runs on `sseTaskExecutor` pool.
- **LLM gateway** (`LlmService`): every call bounded by `app.llm.call-timeout-ms` (30s). Fail-open for classifier and brief-summary steps.
- **Clockwork integration**: REST client to Clockwork Recruiting. Candidate import idempotent by `clockworkId`.
- **Other services**: `ImportProjectService` (Excel/paste bulk ingest), `CompanyService.upsertNonDestructive` (per-field confidence 1–10, never overwrites manual edits), `BriefExtractService` (PDF/DOCX via PDFBox + POI).
- **Cross-cutting**: `web/GlobalExceptionHandler` (sanitized error JSON, no stack traces). Config in `config/`: `AiConfig`, `AsyncConfig`, `SecurityConfig`, `CorsConfig`, `StaticResourceConfig`.

## Project Layout

```
src/
├── main/
│   ├── java/com/globaltalenthub/
│   │   ├── config/         # Spring + AI + security wiring
│   │   ├── controller/     # REST + SSE endpoints
│   │   ├── service/        # Business logic, LLM, pipeline, Clockwork
│   │   ├── repository/     # Spring Data JPA repos
│   │   ├── entity/         # JPA entities (hak_* tables)
│   │   ├── security/       # JwtService, JwtAuthFilter, AuthenticatedUser
│   │   ├── taxonomy/       # Closed sector/band vocabulary
│   │   └── web/            # GlobalExceptionHandler
│   └── resources/
│       ├── application*.properties
│       └── static/         # SPA assets
└── test/java/com/globaltalenthub/
    └── TestIds.java        # Shared test UUIDs — reuse, don't redeclare
```

## Documentation

- [`CLAUDE.md`](CLAUDE.md) — guidance for Claude Code
- [`docs/auth-jwt.md`](docs/auth-jwt.md) — app-owned JWT design
- [`CODE_REVIEW.md`](CODE_REVIEW.md)
- [`SECURITY_REVIEW.md`](SECURITY_REVIEW.md)
- [`INTEGRATION_TESTING.md`](INTEGRATION_TESTING.md)

## Conventions

- Lombok throughout. `lombok.config` copies Spring `@Qualifier`/`@Value` onto generated constructor params.
- No checkstyle/spotless.
- Reuse `TestIds` constants instead of redeclaring `UUID.fromString(...)` literals.
- Many classes carry `port of X.ts` comments tracing their origin from a prior Node/Express + TypeScript codebase.
