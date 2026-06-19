# Prompt Evaluation

End-to-end golden-dataset eval for the AI search classifier + ranking. Catches prompt/scorer
regressions that mocked unit tests can't (it calls the **real** Gemini classifier and the real
ranking query over `company_enrichment`).

## Files

- `classifier_cases.json` — `query → expected` for the classifier (sectors / countries / subTags /
  limit / isListed). Asserted deterministically: sectors & subTags are *superset* checks, countries
  is exact-set, limit & isListed exact when specified.
- `ranking_cases.json` — `query → top-K invariants` over the real GCC data
  (`topAllCountry`, `topAllSector`, `expectCompaniesPresent`, `min/maxResults`).
- Harness: [`PromptEvaluationIT`](../../java/com/globaltalenthub/eval/PromptEvaluationIT.java) +
  `EvalReport` (scorecard).

The datasets are grounded in `docs/company_enrichment_rows.csv` — only sector/country cells with
ample real rows and verified company names (e.g. First Abu Dhabi Bank, Tawuniya) are used.

## Run

Needs live Vertex + DB creds via the `local` profile (`application-local.properties`). It is excluded
from `mvn test` two ways: `@Tag("eval")` (surefire `excludedGroups`) and the `RUN_PROMPT_EVAL` gate.

```bash
RUN_PROMPT_EVAL=true SPRING_PROFILES_ACTIVE=local \
  JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn test -Dtest=PromptEvaluationIT -DexcludedGroups=
```

Both layers print a scorecard and assert a 100% pass rate on the curated set (`PASS_THRESHOLD`).

## Add a case

Append an object to the relevant JSON. Keep assertions grounded in real data:
- Classifier: any closed-vocab value (see `Taxonomy`); countries must be one of the 6 GCC markets.
- Ranking: pick a sector×country cell with enough rows; `expectCompaniesPresent` must use exact
  `company_name` strings from the CSV.

### Choosing ranking asserts (avoid false negatives)
- `topAllCountry` is safe — the country weight should make every top row match the requested country.
- `topAllSector` only holds when you expect a *pure primary-sector* result. By design the scorer also
  surfaces **secondary-sector** matches (`sector_mix` significant/minor, `sector_tags`) — e.g. a Qatar
  real-estate group whose `sector_mix` lists Capital Markets will legitimately appear for a capital
  markets query. Set `topAllSector: null` for any query where secondary-sector recall is expected.
- `expectCompaniesPresent` is brittle for large cells (many rows tie on confidence, so a specific
  company can fall just outside top-K). Use it only for marquee, unambiguous leaders (e.g. "First Abu
  Dhabi Bank" for UAE banking), not for crowded fields.

## Note on `is_listed`

Every enrichment row currently has an empty `is_listed`, so ranking cannot filter on it — only the
*classifier* `expectIsListed` cases (query → extracted flag) are meaningful today.
