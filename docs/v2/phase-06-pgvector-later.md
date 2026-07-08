# Phase 06 — Semantic search via pgvector (deferred)

> **Deferred — not built this round.** Placeholder so the follow-up is scoped and the phase-01
> "SQL filters first" decision has a documented next step.

## Goal

Add meaning-based ranking on top of the phase-01 SQL filter. The catalog already stages a
MEANING-ONLY `search_text` column (name + slogan + description + industry_tags + specialties +
primary_industry + hq_country — see `docs/04_app_companies.sql`) precisely for this.

## What it will involve (high level)

- **DB**: enable the `pgvector` extension; add `embedding VECTOR(n)` to `app_companies`
  (dimension = chosen embedding model); index it (IVFFlat/HNSW). New SQL file
  `docs/06_app_companies_embedding.sql`.
- **Backfill**: embed every row's `search_text` into `embedding` (batch loader, mirrors the
  existing `build_app_companies.py` loader style). Re-embed on rebuild.
- **Query embedding**: embed the user's query text at request time (reuse the existing embedding
  gateway — the app already depends on `spring-ai-starter-model-vertex-ai-embedding`; confirm the
  model + dimension against the current `LlmService`/AI config before choosing `n`).
- **Ranking**: keep phase-01 SQL as the **recall gate** (bounded candidate pool), then **re-rank
  the pool by cosine distance** (`embedding <=> :queryVec`) — do not full-scan 54k vectors per
  request. Mirrors the existing hybrid pattern documented in `docs/dnb-integration-analysis.md`
  (LLM intent → hard SQL filter → vector re-rank).
- **API**: extend `GET /api/app/companies/search` with an optional semantic mode
  (e.g. `&semantic=true`) or a sibling endpoint; response shape unchanged (`Page<AppCompanyDto>`),
  just reordered by relevance. Additive — phase-01 behavior stays the default.

## UI

No new screens — the existing phase-01 search/results UI consumes the reordered results as-is.

## Test / verify (when built)

- Embedding backfill covers 100% of rows; dimension matches the column.
- A semantic query ("companies like Almarai") surfaces relevant rows the pure-SQL filter misses.
- Latency acceptable: pool-bounded re-rank, not a full vector scan.
- `JAVA_HOME=…jdk-21… mvn test` green.

## Not now

Left out deliberately this round to ship the SQL path first (per the confirmed phase-01 decision).
Pick up only after phases 00–05 are stable.
