# Security Review — global-talent-hub-spring

**Date:** 2026-06-13
**Reviewer:** Claude (Opus 4.8)
**Method:** Manual static security sweep. `/security-review` skill requires a git repo; this directory is not initialized, so the review was performed by hand. Read-only — no code changed.

## Scope swept
Auth/JWT, multi-tenant (org) isolation, SQL injection, file upload/parsing, outbound HTTP (Clockwork), CORS, secrets handling, config hardening, error leakage, SPA/static serving.

## Summary
Security posture is **strong** — org isolation is consistent, no SQL injection, no secret logging, no disk writes, error handler sanitizes. **One high-severity gap (S1, Clockwork cross-tenant exposure)** plus a handful of medium hardening items.

---

## HIGH

### S1. Clockwork endpoints have no org scoping or role check — cross-tenant data exposure
[ClockworkController.java:27-44](src/main/java/com/globaltalenthub/controller/ClockworkController.java#L27-L44). `/api/clockwork/diagnostics`, `/api/clockwork/projects`, `/api/clockwork/projects/{projectId}/people` take no `orgId`/role and call the shared firm-wide Clockwork API. **Any authenticated user (any org) can enumerate all projects and people for the whole recruiting firm.** `projectId` is caller-supplied and proxied straight through ([ClockworkApiClient.getProjectPeople](src/main/java/com/globaltalenthub/service/clockwork/ClockworkApiClient.java#L60)). Sensitive candidate PII leaks across tenants.

**Fix direction:** gate behind org membership + role; restrict which projects an org may read (map `searchQuery.clockworkProjectId` → org).

---

## MEDIUM

### S2. Two overlapping config sources (`application.yml` + `application.properties`)
Both exist and both define datasource, JPA, AI, `app.supabase.jwt-secret` (placeholder default). Spring loads `.properties` over `.yml` for the same keys, but the drift is a foot-gun — a security setting fixed in one file can be silently overridden by the other. Consolidate to one. (The H1 fail-fast in `SupabaseJwtFilter` still protects the placeholder secret either way.)

### S3. File parsing — XXE / zip-bomb surface (PDFBox + Apache POI)
[BriefExtractService.java:41-52](src/main/java/com/globaltalenthub/service/BriefExtractService.java#L41-L52). DOCX (POI/OOXML = zip) and PDF parsed from untrusted upload. The 10MB multipart cap limits raw size, but a 10MB OOXML can decompress to GBs (zip bomb → OOM DoS); POI XML parsing is an XXE vector on older configs. poi-ooxml 5.2.5 ships `ZipSecureFile` ratio guards on by default — confirm not disabled. Recommend explicit `ZipSecureFile.setMinInflateRatio` / max entry size, and run extraction off the request thread with a timeout.

### S4. SSE access token accepted via query param globally (`?access_token=`)
[SupabaseJwtFilter.java:53-61](src/main/java/com/globaltalenthub/security/SupabaseJwtFilter.java#L53-L61). JWT in URL → leaks into proxy/access logs, browser history, Referer. Required for EventSource only — scope this path to the SSE route, and strip `access_token` from access logging.

### S5. JWT validation: no issuer/audience binding
[SupabaseJwtFilter.java:74-78](src/main/java/com/globaltalenthub/security/SupabaseJwtFilter.java#L74-L78). Signature + expiry are checked (jjwt rejects expired by default). But no `requireIssuer`/`requireAudience` — any token signed with the same HMAC secret (another Supabase project sharing it, or a different token type) is accepted. Pin issuer + audience.

---

## LOW

### S6. CORS allows all headers + credentials
[CorsConfig.java:25-26](src/main/java/com/globaltalenthub/config/CorsConfig.java#L25-L26). `allowedHeaders("*")` with `allowCredentials(true)`. Origins are now env-pinned (good — no `*`), so impact is limited. Narrow headers to those actually used (`Authorization`, `Content-Type`).

### S7. `/api/config` is public and returns the Mapbox token
[SecurityConfig.java](src/main/java/com/globaltalenthub/config/SecurityConfig.java) permitAll + [ConfigController.java](src/main/java/com/globaltalenthub/controller/ConfigController.java). Mapbox is a public client token (acceptable), but confirm it is URL-restricted in the Mapbox console so a leaked token can't be abused for billing.

### S8. Unbounded error strings returned from import
[ImportProjectService.java:108](src/main/java/com/globaltalenthub/service/ImportProjectService.java#L108) collects raw `ex.getMessage()` into the response `errors` list. Low risk (internal exceptions), but could echo DB/internal detail to the client. Map to generic per-row messages.

---

## Positives (verified)
- **Org isolation consistent** across all entity endpoints: every `@PathVariable` handler threads `user.orgId()` → service → `findByIdAndOrgId` / `OrgGuardService.assert*InOrg` (Companies, Executives, Notes, CareerHistory, Education, Remuneration, Dashboard, SearchEnrich, Import). **S1 (Clockwork) is the lone exception.**
- **No SQL injection.** Native queries use bound `@Param`; array params cast to `text[]`; ILIKE uses bound `:name`. [CompanyEnrichmentRepository.java:30-49](src/main/java/com/globaltalenthub/repository/CompanyEnrichmentRepository.java#L30-L49), [CompanyRepository.java:23](src/main/java/com/globaltalenthub/repository/CompanyRepository.java#L23).
- **No secret logging** — no token/secret/password/jwt values in any log statement. Clockwork logs the endpoint path only.
- **No path traversal / disk writes** — `app.upload-dir` is configured but unused in code; uploads parsed in-memory, never written to disk. Filename used only for extension matching.
- **Config hardening:** `ddl-auto=none` (prod), `show-sql=false` (prod), no actuator/h2-console exposed, multipart capped at 10MB, stateless sessions, CSRF disabled (correct for a stateless JWT API).
- **Error handler sanitizes** — generic 500/400 messages, detail logged server-side ([GlobalExceptionHandler.java](src/main/java/com/globaltalenthub/web/GlobalExceptionHandler.java)).
- **JWT secret fail-fast** on placeholder in non-test profile ([SupabaseJwtFilter.java:44-51](src/main/java/com/globaltalenthub/security/SupabaseJwtFilter.java#L44-L51)).
- **Null-org tenant guard** — valid token without org membership is 403'd on org-scoped routes ([SupabaseJwtFilter.java:88-91](src/main/java/com/globaltalenthub/security/SupabaseJwtFilter.java#L88-L91)).

---

## Recommended order
1. **S1** (Clockwork cross-tenant) — highest impact, fix first.
2. S3 (file-parse hardening), S4 (SSE token scope), S5 (issuer/audience pin).
3. S2 (config consolidation), S6 / S7 / S8 cleanup.
