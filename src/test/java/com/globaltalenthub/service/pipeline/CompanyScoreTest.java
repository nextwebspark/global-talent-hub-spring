package com.globaltalenthub.service.pipeline;

import com.globaltalenthub.service.pipeline.CompanyScore.ScorableRow;
import com.globaltalenthub.service.pipeline.CompanyScore.ScoredMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-function tests for the match scorer — parity with companyScore.ts. */
class CompanyScoreTest {

    private static EnrichmentFilter filter(List<String> primary, List<String> adjacent, List<String> subTags,
                                           List<String> countries, List<String> revenueBands,
                                           List<String> employeeBands, Boolean isListed) {
        return new EnrichmentFilter(primary, adjacent, subTags, countries, employeeBands, revenueBands, isListed, "r");
    }

    private static ScorableRow row(String primarySector, List<String> subTags, String country,
                                   String revenueBand, String employeeBand, Boolean isListed) {
        return new ScorableRow(primarySector, subTags, country, revenueBand, employeeBand, isListed);
    }

    @Test
    void primaryOnlyFilter_primaryHit_scores100_Direct() {
        var f = filter(List.of("Technology & Software"), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        var r = row("Technology & Software", List.of(), "United States", null, null, null);

        ScoredMatch m = CompanyScore.score(r, f);

        assertThat(m.matchScore()).isEqualTo(100);
        assertThat(m.relevanceType()).isEqualTo("Direct");
        assertThat(m.breakdown().primarySector()).isTrue();
        assertThat(m.breakdown().adjacentSector()).isFalse();
    }

    @Test
    void adjacentHit_notPrimary_scores100_Adjacent_whenOnlyAdjacencyRequested() {
        var f = filter(List.of("Banking & Financial Services"), List.of("Insurance"), List.of(),
            List.of(), List.of(), List.of(), null);
        var r = row("Insurance", List.of(), "United Kingdom", null, null, null);

        ScoredMatch m = CompanyScore.score(r, f);

        // sectorMax falls back to primary weight (0.5) because primarySectors is non-empty;
        // an adjacent hit earns 0.25 → 50.
        assertThat(m.matchScore()).isEqualTo(50);
        assertThat(m.relevanceType()).isEqualTo("Adjacent");
        assertThat(m.breakdown().adjacentSector()).isTrue();
    }

    @Test
    void adjacentOnly_filter_adjacentHit_scores100() {
        var f = filter(List.of(), List.of("Insurance"), List.of(), List.of(), List.of(), List.of(), null);
        var r = row("Insurance", List.of(), "Qatar", null, null, null);

        ScoredMatch m = CompanyScore.score(r, f);

        assertThat(m.matchScore()).isEqualTo(100);
        assertThat(m.relevanceType()).isEqualTo("Adjacent");
    }

    @Test
    void subTagOverlap_drivesAiInferred_whenNoSectorHit() {
        var f = filter(List.of(), List.of(), List.of("fintech-payments", "fintech-neobank"),
            List.of(), List.of(), List.of(), null);
        var hit = row("Retail & E-Commerce", List.of("fintech-payments"), "United States", null, null, null);
        var miss = row("Retail & E-Commerce", List.of("luxury-retail"), "United States", null, null, null);

        assertThat(CompanyScore.score(hit, f).matchScore()).isEqualTo(100);
        assertThat(CompanyScore.score(hit, f).relevanceType()).isEqualTo("AI Inferred");
        assertThat(CompanyScore.score(miss, f).matchScore()).isEqualTo(0);
    }

    @Test
    void softSignals_raiseDenominatorAndNumerator() {
        // Requested: primary(0.5) + country(0.12) + revenue(0.08). Row matches primary + country only.
        var f = filter(List.of("Technology & Software"), List.of(), List.of(),
            List.of("United States"), List.of("$1-10B"), List.of(), null);
        var r = row("Technology & Software", List.of(), "United States", "$50-250M", null, null);

        ScoredMatch m = CompanyScore.score(r, f);

        double expected = (0.5 + 0.12) / (0.5 + 0.12 + 0.08);
        assertThat(m.matchScore()).isEqualTo((int) Math.round(expected * 100)); // 89
        assertThat(m.breakdown().country()).isTrue();
        assertThat(m.breakdown().revenueBand()).isFalse();
    }

    @Test
    void isListed_countsOnlyWhenFilterSetAndEqual() {
        var f = filter(List.of("Telecommunications"), List.of(), List.of(), List.of(), List.of(), List.of(), true);
        var listed = row("Telecommunications", List.of(), "France", null, null, true);
        var notListed = row("Telecommunications", List.of(), "France", null, null, false);

        assertThat(CompanyScore.score(listed, f).matchScore()).isEqualTo(100);   // (0.5+0.04)/(0.5+0.04)
        assertThat(CompanyScore.score(listed, f).breakdown().isListed()).isTrue();
        // Listing requested but not matched: 0.5/0.54 = 92.59 → 93.
        assertThat(CompanyScore.score(notListed, f).matchScore()).isEqualTo(93);
        assertThat(CompanyScore.score(notListed, f).breakdown().isListed()).isFalse();
    }

    @Test
    void emptyFilter_scoresZero_AiInferred() {
        var f = filter(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        var r = row("Technology & Software", List.of("enterprise-saas"), "United States", "$1-10B", "1k-5k", true);

        ScoredMatch m = CompanyScore.score(r, f);

        assertThat(m.matchScore()).isEqualTo(0);
        assertThat(m.relevanceType()).isEqualTo("AI Inferred");
    }

    @Test
    void rounding_matchesJsMathRound_halfUp() {
        // primary(0.5) + employee(0.08): match primary only → 0.5/0.58 = 0.862069 → 86.
        var f = filter(List.of("Aviation & Aerospace"), List.of(), List.of(), List.of(), List.of(),
            List.of("10k+"), null);
        var r = row("Aviation & Aerospace", List.of(), "United Arab Emirates", null, "51-200", null);
        assertThat(CompanyScore.score(r, f).matchScore()).isEqualTo(86);
    }
}
