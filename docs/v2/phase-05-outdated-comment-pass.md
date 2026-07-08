# Phase 05 — Mark superseded existing code `// OUTDATED:`

## Goal

Now that the `/api/app/*` flow replaces parts of the old `/api/*` surface, make the legacy code
discoverable without changing behavior. Add a one-line `// OUTDATED:` comment above each superseded
method/class pointing to its replacement. **Comment-only — no logic, signature, or route edits.**

## Depends on

Phases 01–04 landed (so the replacements actually exist and are pointed to).

## What to do

For each existing method the new flow supersedes, add a comment directly above it:

```
// OUTDATED: superseded by <NewClass>.<method> (/api/app/...); kept for the existing UI.
```

If a method **still has live callers** (existing UI/tests), do **not** call it outdated — instead
note who uses it:

```
// NOTE: still used by <caller>; new flow uses <NewClass> (/api/app/...).
```

### Candidates (verify each is actually superseded before commenting)

| Existing | Replaced by | Path |
|---|---|---|
| `CompanyController.getAll` / `search` | `AppCompanyController` search/facets | `controller/CompanyController.java` |
| `CompanyController.getOne` | `AppCompanyController.getOne` | same |
| `SearchController` POST `/api/search` (+ stream) | `AppSearchRunController` (phase 02, non-streaming) | `controller/SearchController.java` |
| `SearchQueryController` (universe/project persistence) | `AppProjectController` (phase 03) | `controller/SearchQueryController.java` |

Confirm supersession per method (grep for callers in the existing UI + tests). The old SSE search
pipeline is still wired to the current UI — comment it as OUTDATED only if the current UI no longer
calls it; otherwise use the NOTE form. When unsure, use NOTE, not OUTDATED.

## Guardrails

- Only comments change. Diff must be **comment-line additions only** — zero behavior edits to any
  `hak_*` entity or existing controller/service.
- Do not delete pre-existing dead code (project convention: mention, don't remove).

## Test / verify

- `JAVA_HOME=…jdk-21… mvn test` — still fully green (no behavior touched).
- `git diff` shows only added comment lines in existing files.
- `grep -rn "OUTDATED:" src/main/java` lists exactly the intended methods.

## Done when

- [ ] Every superseded method carries an accurate `// OUTDATED:` (or `// NOTE:` if still used).
- [ ] `mvn test` green; diff is comment-only.
- [ ] Master tracker (`README.md`) phase 05 marked done.
