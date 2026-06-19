package com.globaltalenthub.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globaltalenthub.service.pipeline.CompanyEnrichmentQueryService;
import com.globaltalenthub.service.pipeline.EnrichedCompanyMatch;
import com.globaltalenthub.service.pipeline.EnrichmentFilter;
import com.globaltalenthub.service.pipeline.EnrichmentFilterService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end prompt evaluation against a curated golden dataset — NOT a unit test.
 *
 * <p>Calls the REAL classifier LLM (Vertex Gemini) and the real ranking query, so it needs live
 * credentials and runs under the {@code local} profile (gitignored {@code application-local.properties}).
 * It is excluded from {@code mvn test} two ways: tagged {@code eval} (surefire {@code excludedGroups})
 * and gated on {@code RUN_PROMPT_EVAL=true}. Run on demand:
 *
 * <pre>
 * RUN_PROMPT_EVAL=true SPRING_PROFILES_ACTIVE=local \
 *   JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
 *   mvn test -Dtest=PromptEvaluationIT -DexcludedGroups=
 * </pre>
 *
 * <p>Two layers are scored against deterministic expectations:
 * <ol>
 *   <li><b>Classifier</b> — query → {@link EnrichmentFilter} (sectors / countries / subTags / limit).</li>
 *   <li><b>Ranking</b> — query → ordered companies; top-K invariants on the real GCC data.</li>
 * </ol>
 * Each layer prints a scorecard and asserts a pass-rate threshold.
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "RUN_PROMPT_EVAL", matches = "true")
class PromptEvaluationIT {

    /** Curated set is small and authoritative — every case must pass. */
    private static final double PASS_THRESHOLD = 1.0;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    EnrichmentFilterService filterService;

    @Autowired
    CompanyEnrichmentQueryService queryService;

    // ── Layer 1: classifier extraction ────────────────────────────────────────

    record ClassifierCase(String name, String query,
                          List<String> expectPrimarySectors, List<String> expectCountries,
                          List<String> expectSubTagsContains, Integer expectLimit, Boolean expectIsListed) {}

    @Test
    void classifierExtraction_meetsGolden() throws Exception {
        List<ClassifierCase> cases = loadCases("eval/classifier_cases.json", ClassifierCase[].class);
        EvalReport report = new EvalReport("CLASSIFIER");

        for (ClassifierCase c : cases) {
            EnrichmentFilter f = filterService.extract(c.query(), null);
            List<String> fails = new ArrayList<>();

            if (!f.primarySectors().containsAll(c.expectPrimarySectors())) {
                fails.add("sectors=" + f.primarySectors() + " ⊉ " + c.expectPrimarySectors());
            }
            if (!equalsAsSet(f.countries(), c.expectCountries())) {
                fails.add("countries=" + f.countries() + " ≠ " + c.expectCountries());
            }
            if (!f.subTags().containsAll(c.expectSubTagsContains())) {
                fails.add("subTags=" + f.subTags() + " ⊉ " + c.expectSubTagsContains());
            }
            if (c.expectLimit() != null && f.limit() != c.expectLimit()) {
                fails.add("limit=" + f.limit() + " ≠ " + c.expectLimit());
            }
            if (c.expectIsListed() != null && !Objects.equals(f.isListed(), c.expectIsListed())) {
                fails.add("isListed=" + f.isListed() + " ≠ " + c.expectIsListed());
            }
            report.record(c.name(), c.query(), fails);
        }

        System.out.println(report);
        assertThat(report.passRate())
            .as("classifier pass rate%n%s", report)
            .isGreaterThanOrEqualTo(PASS_THRESHOLD);
    }

    // ── Layer 2: ranking invariants ───────────────────────────────────────────

    record RankingCase(String name, String query, int topK,
                       String topAllCountry, String topAllSector,
                       List<String> expectCompaniesPresent, Integer minResults, Integer maxResults) {}

    @Test
    void rankingInvariants_meetGolden() throws Exception {
        List<RankingCase> cases = loadCases("eval/ranking_cases.json", RankingCase[].class);
        EvalReport report = new EvalReport("RANKING");

        for (RankingCase c : cases) {
            EnrichmentFilter f = filterService.extract(c.query(), null);
            List<EnrichedCompanyMatch> results = queryService.query(f, f.limit());
            List<EnrichedCompanyMatch> top = results.stream().limit(c.topK()).toList();
            List<String> fails = new ArrayList<>();

            if (c.minResults() != null && results.size() < c.minResults()) {
                fails.add("results=" + results.size() + " < min " + c.minResults());
            }
            if (c.maxResults() != null && results.size() > c.maxResults()) {
                fails.add("results=" + results.size() + " > max " + c.maxResults());
            }
            if (c.topAllCountry() != null) {
                long off = top.stream().filter(m -> !c.topAllCountry().equals(m.row().country())).count();
                if (off > 0) fails.add(off + "/" + top.size() + " top rows not in " + c.topAllCountry());
            }
            if (c.topAllSector() != null) {
                long off = top.stream().filter(m -> !c.topAllSector().equals(m.row().primarySector())).count();
                if (off > 0) fails.add(off + "/" + top.size() + " top rows not sector " + c.topAllSector());
            }
            for (String name : c.expectCompaniesPresent()) {
                boolean present = top.stream().anyMatch(m -> name.equalsIgnoreCase(m.row().companyName()));
                if (!present) fails.add("missing expected company: " + name);
            }
            report.record(c.name(), c.query() + "  [" + top.size() + " of " + results.size() + "]", fails);
        }

        System.out.println(report);
        assertThat(report.passRate())
            .as("ranking pass rate%n%s", report)
            .isGreaterThanOrEqualTo(PASS_THRESHOLD);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static boolean equalsAsSet(List<String> a, List<String> b) {
        return new java.util.HashSet<>(a).equals(new java.util.HashSet<>(b));
    }

    private <T> List<T> loadCases(String path, Class<T[]> type) throws Exception {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return List.of(MAPPER.readValue(in, type));
        }
    }
}
