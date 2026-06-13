# Code Review — global-talent-hub-spring

**Date:** 2026-06-13
**Reviewer:** Claude (Opus 4.8)
**Scope:** `src/main/java` (~6.4k LOC), config, security, tests. Static review; build not executed (offline dep risk).

## Summary

Spring Boot 3.3.5 / Java 21 app. AI-powered talent/company search; port of a prior Node/Express+TS codebase (many "port of X.ts" comments). Vertex AI Gemini for classification/enrichment, Supabase JWT auth, Postgres/JPA, SSE streaming search pipeline.

Overall: **well-structured and readable.** Clear layering (controller → service → repository), narrow seams (`LlmClassifier`), thoughtful non-destructive merge with provenance. Good test coverage (24 test classes, ~120 `@Test`). Main concerns are **multi-tenant security gaps** and a few correctness/robustness bugs.

---

## High — security / correctness

### H1. JWT signature only — no expiry/issuer/audience validation enforced
`SupabaseJwtFilter` ([SupabaseJwtFilter.java:53-58](src/main/java/com/globaltalenthub/security/SupabaseJwtFilter.java#L53-L58)) verifies HMAC signature but never checks `exp`, `iss`, `aud`.
- jjwt `parseSignedClaims` *does* reject expired tokens by default — OK. But no issuer/audience binding. A valid Supabase token from a *different* project signed with same leaked secret would pass. Pin `requireIssuer` / `requireAudience`.
- HMAC secret from env (`SUPABASE_JWT_SECRET`), default `placeholder-for-tests`. If that default ever ships to prod (missing env), every forged token validates. Fail-fast on startup if secret == placeholder and profile != test.

### H2. Authenticated user with no org can reach org-scoped endpoints
JWT filter sets principal even when `orgMemberRepo.findByUserId` returns empty → `orgId = null` ([SupabaseJwtFilter.java:61-67](src/main/java/com/globaltalenthub/security/SupabaseJwtFilter.java#L61-L67)).
Controllers then call `user.orgId()` (null) into repos like `findByOrgId(null)`, `upsertNonDestructive(..., orgId=null, ...)`. Behavior on null orgId is undefined/leaky — a user without membership could read/write rows where `org_id IS NULL`.
**Fix:** central guard — reject requests with null orgId on all `/api/**` except `/api/auth/**`, `/api/config`, `/api/health`. Either a filter/interceptor or `@AuthenticationPrincipal` validation.

### H3. SSE token via query param logged in access logs
`extractToken` accepts `?access_token=` ([SupabaseJwtFilter.java:39-40](src/main/java/com/globaltalenthub/security/SupabaseJwtFilter.java#L39-L40)). Needed for EventSource (documented). But JWTs in URLs land in proxy/access logs and browser history. Acceptable tradeoff but: scope the query-param path to the SSE route only, and ensure access logging strips it.

### H4. CORS origins hardcoded to localhost
`CorsConfig` ([CorsConfig.java:17-20](src/main/java/com/globaltalenthub/config/CorsConfig.java#L17-L20)) allows only `localhost:3000/5000` with `allowCredentials=true`. Prod frontend origin will be blocked, or someone widens to `*` (illegal with credentials). Externalize origins to config/env per environment.

### H5. Member email never captured
`AuthController` passes `null` for email into both `signupOrg` and `me` ([AuthController.java:29](src/main/java/com/globaltalenthub/controller/AuthController.java#L29), [:34](src/main/java/com/globaltalenthub/controller/AuthController.java#L34)). So `OrgMember.email` is always null and `/api/auth/me` returns null email. The email claim exists in the Supabase JWT — `AuthenticatedUser` should carry it and the filter should populate it from `claims.get("email")`.

---

## Medium — robustness / bugs

### M1. `OrgGuardService.assertCareerHistoryInOrg` double-fetches + wrong-order check
[OrgGuardService.java:29-38](src/main/java/com/globaltalenthub/service/OrgGuardService.java#L29-L38): `findById` called twice; first block silently no-ops if record absent (`ifPresent`), real existence error only thrown by second block. Collapse to one fetch:
```java
var ch = careerHistoryRepo.findById(id)
    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Career history not found"));
executiveRepo.findByIdAndOrgId(ch.getExecutiveId(), orgId)
    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Career history not found"));
```

### M2. `buildBriefContext` substring can throw / mid-char cut
[SearchController.java:282](src/main/java/com/globaltalenthub/controller/SearchController.java#L282): `pdContent.substring(0, min(len, LIMIT))` is bounds-safe, fine. But cuts mid-word/mid-codepoint silently — minor. No bug, note only.

### M3. Generic exception handler hides ResponseStatusException subclasses? No — but leaks `IllegalArgumentException` messages
`handleBadRequest` ([GlobalExceptionHandler.java:33-37](src/main/java/com/globaltalenthub/web/GlobalExceptionHandler.java#L33-L37)) returns raw `ex.getMessage()` to client. If any internal code throws IAE with sensitive detail, it leaks. Low risk, but prefer a fixed message or sanitize.

### M4. LLM fallback double-charges / no timeout
`callWithFallback` ([LlmService.java:32-39](src/main/java/com/globaltalenthub/service/LlmService.java#L32-L39)) catches *any* Exception from pro and retries on flash. No timeout configured → a hung pro call blocks the SSE worker up to the 5-min SSE timeout. Add per-call timeout; consider not falling back on non-retryable errors (e.g. bad prompt / 4xx).

### M5. `nowIso()` uses `OffsetDateTime.now()` (server local offset)
[CompanyService.java:384-386](src/main/java/com/globaltalenthub/service/CompanyService.java#L384-L386). Provenance timestamps depend on server tz. Use `OffsetDateTime.now(ZoneOffset.UTC)` or `Instant.now()` for stable, comparable history entries.

### M6. `add-to-project` unchecked casts can 500 on bad input
[SearchController.java:252-258](src/main/java/com/globaltalenthub/controller/SearchController.java#L252-L258): `(List<Object>) body.get("companyIds")` and `((Number) o)` throw `ClassCastException` → generic 500, not 400. Validate shape, throw `ResponseStatusException(BAD_REQUEST)`.

---

## Low — style / maintainability

- **L1.** Inline fully-qualified class names instead of imports in `SearchController` (`com.globaltalenthub.service.SearchManagementService...` at [:59](src/main/java/com/globaltalenthub/controller/SearchController.java#L59), [:183](src/main/java/com/globaltalenthub/controller/SearchController.java#L183), [:250](src/main/java/com/globaltalenthub/controller/SearchController.java#L250)) and FQNs scattered in `CompanyService`. Hurts readability vs. rest of codebase. Add imports.
- **L2.** `AiConfig.geminiFlash` ([AiConfig.java:32-42](src/main/java/com/globaltalenthub/config/AiConfig.java#L32-L42)) injects `@Value app.fast-model` but `application.yml` also defines `app.fast-model` from `FAST_MODEL` — the `:gemini-2.5-flash` default in the annotation is dead (yml always supplies value). Harmless; drop one source of truth.
- **L3.** `provEntryWithHistory` ([CompanyService.java:355-357](src/main/java/com/globaltalenthub/service/CompanyService.java#L355-L357)) just delegates to `provEntry`. Inline or remove.
- **L4.** `FIELD_CONFIDENCES = Map.of("country", 7, "sector", 7)` ([SearchPipelineService.java:28](src/main/java/com/globaltalenthub/service/pipeline/SearchPipelineService.java#L28)) — magic numbers; document why 7 / where the 1–10 scale is defined.
- **L5.** `ConfigController` ([ConfigController.java:12](src/main/java/com/globaltalenthub/controller/ConfigController.java#L12)) reads `MAPBOX_ACCESS_TOKEN` env directly (uppercase) rather than via the `app.*` config tree used elsewhere. Inconsistent; and `/api/config` is `permitAll` so the mapbox token is public — confirm that's intended (it's a client token, usually OK).
- **L6.** `SearchPipelineService` iterates `rows` twice ([:82](src/main/java/com/globaltalenthub/service/pipeline/SearchPipelineService.java#L82), [:93](src/main/java/com/globaltalenthub/service/pipeline/SearchPipelineService.java#L93)) — first emits `company_found`, then persists. Intentional (UX: show found list fast). Fine, just note the N+1 upsert in the second loop is the real cost.

---

## Positives

- Clean non-destructive upsert w/ confidence-weighted merge + provenance history ([CompanyService.java:201-267](src/main/java/com/globaltalenthub/service/CompanyService.java#L201-L267)). Core invariant well-modeled.
- Good test seam: `LlmClassifier` interface lets pipeline run without Vertex beans; AI config excluded from `test` profile.
- Consistent org-scoping pattern (`findByIdAndOrgId`) across repos — H2 is the one gap.
- Central error mapping to `{"error": ...}` matches client contract.
- SSE proxy headers (`X-Accel-Buffering: no`) — correct for Railway/Nginx.

---

## Recommended order

1. H2 (null-org access) + H1 (issuer/secret fail-fast) — tenant isolation.
2. H5 (email capture) + H4 (CORS env) — needed for prod.
3. M1, M4, M6 — robustness.
4. Low items as cleanup pass.
