package com.globaltalenthub.service.pipeline;

import com.globaltalenthub.service.pipeline.CompanyScore.ScorableRow;
import com.globaltalenthub.service.pipeline.CompanyScore.ScoredMatch;
import com.globaltalenthub.service.pipeline.EnrichedRow.SectorWeight;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-function tests for the match scorer — country weight + sector_mix ladder + sector_tags. */
class CompanyScoreTest {

    private static EnrichmentFilter filter(List<String> primary, List<String> adjacent, List<String> subTags,
                                           List<String> countries, List<String> revenueBands,
                                           List<String> employeeBands, Boolean isListed) {
        return new EnrichmentFilter(primary, adjacent, subTags, countries, employeeBands, revenueBands, isListed, "r", 20);
    }

    private static ScorableRow row(String primarySector, List<String> sectorTags, List<SectorWeight> sectorMix,
                                   List<String> subTags, String country,
                                   String revenueBand, String employeeBand, Boolean isListed) {
        return new ScorableRow(primarySector, sectorTags, sectorMix, subTags, country, revenueBand, employeeBand, isListed);
    }

    // Convenience for sector-only rows (no tags / mix / soft fields).
    private static ScorableRow plain(String primarySector, String country) {
        return row(primarySector, List.of(), List.of(), List.of(), country, null, null, null);
    }

    @Test
    void primaryOnlyFilter_primaryHit_scores100_Direct() {
        var f = filter(List.of("Technology & Software"), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        var r = plain("Technology & Software", "United States");

        ScoredMatch m = CompanyScore.score(r, f);

        assertThat(m.matchScore()).isEqualTo(100);
        assertThat(m.relevanceType()).isEqualTo("Direct");
        assertThat(m.breakdown().primarySector()).isTrue();
        assertThat(m.breakdown().adjacentSector()).isFalse();
    }

    @Test
    void countryMatch_outranksCountryMismatch_butMismatchStillScores() {
        // Same sector; one in the requested country, one not. Country is requested.
        var f = filter(List.of("Banking & Financial Services"), List.of(), List.of(),
            List.of("United Arab Emirates"), List.of(), List.of(), null);
        var inCountry = plain("Banking & Financial Services", "United Arab Emirates");
        var wrongCountry = plain("Banking & Financial Services", "Singapore");

        int inScore = CompanyScore.score(inCountry, f).matchScore();
        int wrongScore = CompanyScore.score(wrongCountry, f).matchScore();

        assertThat(inScore).isEqualTo(100);          // (0.5 + 0.5) / (0.5 + 0.5)
        assertThat(wrongScore).isEqualTo(50);         // 0.5 / 1.0 — low confidence, still > 0
        assertThat(inScore).isGreaterThan(wrongScore);
        assertThat(CompanyScore.score(wrongCountry, f).breakdown().country()).isFalse();
    }

    @Test
    void noCountryRequested_rankByOtherFactors_countryDimensionAbsent() {
        // When the classifier returns no country, the country dimension must not enter the
        // score at all — ranking is purely sector/sub_tag. Rows in different countries with the
        // same sector score identically.
        var f = filter(List.of("Banking & Financial Services"), List.of(), List.of(),
            List.of(), List.of(), List.of(), null);
        var uae = plain("Banking & Financial Services", "United Arab Emirates");
        var qatar = plain("Banking & Financial Services", "Qatar");

        assertThat(CompanyScore.score(uae, f).matchScore()).isEqualTo(100);
        assertThat(CompanyScore.score(qatar, f).matchScore()).isEqualTo(100);
        assertThat(CompanyScore.score(uae, f).breakdown().country()).isFalse();
    }

    @Test
    void sectorMixLadder_significantAndMinor_scoreBelowPrimary() {
        // Row: Banking dominant / Capital Markets significant / Insurance minor.
        var mix = List.of(
            new SectorWeight("Banking & Financial Services", "dominant"),
            new SectorWeight("Capital Markets & Asset Management", "significant"),
            new SectorWeight("Insurance", "minor"));
        var r = row("Banking & Financial Services", List.of(), mix, List.of(), "United Arab Emirates", null, null, null);

        // Querying the dominant/primary sector → Direct, 100 (sector_mix ignored).
        var bankingF = filter(List.of("Banking & Financial Services"), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        assertThat(CompanyScore.score(r, bankingF).matchScore()).isEqualTo(100);
        assertThat(CompanyScore.score(r, bankingF).relevanceType()).isEqualTo("Direct");

        // Querying the "significant" sector → Adjacent, 0.35/0.50 = 70.
        var capF = filter(List.of("Capital Markets & Asset Management"), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        assertThat(CompanyScore.score(r, capF).matchScore()).isEqualTo(70);
        assertThat(CompanyScore.score(r, capF).relevanceType()).isEqualTo("Adjacent");

        // Querying the "minor" sector → AI Inferred, 0.20/0.50 = 40.
        var insF = filter(List.of("Insurance"), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        assertThat(CompanyScore.score(r, insF).matchScore()).isEqualTo(40);
        assertThat(CompanyScore.score(r, insF).relevanceType()).isEqualTo("AI Inferred");
    }

    @Test
    void primaryHit_ignoresSectorMix_noDoubleCount() {
        // Primary already matches; a significant sector_mix entry for the same query must not add.
        var mix = List.of(new SectorWeight("Technology & Software", "significant"));
        var r = row("Technology & Software", List.of(), mix, List.of(), "United States", null, null, null);
        var f = filter(List.of("Technology & Software"), List.of(), List.of(), List.of(), List.of(), List.of(), null);

        assertThat(CompanyScore.score(r, f).matchScore()).isEqualTo(100);   // not > 100
        assertThat(CompanyScore.score(r, f).relevanceType()).isEqualTo("Direct");
    }

    @Test
    void sectorTagsOverlap_onlySignal_surfacesLow() {
        // No primary / sector_mix / adjacency hit — only sector_tags overlaps the requested sector.
        var r = row("Retail & E-Commerce", List.of("Technology & Software"), List.of(), List.of(),
            "United States", null, null, null);
        var f = filter(List.of("Technology & Software"), List.of(), List.of(), List.of(), List.of(), List.of(), null);

        ScoredMatch m = CompanyScore.score(r, f);
        assertThat(m.matchScore()).isEqualTo(30);   // 0.15 / 0.50
        assertThat(m.relevanceType()).isEqualTo("AI Inferred");
    }

    @Test
    void adjacencyMapHit_notPrimary_scores50_whenPrimaryRequested() {
        var f = filter(List.of("Banking & Financial Services"), List.of("Insurance"), List.of(),
            List.of(), List.of(), List.of(), null);
        var r = plain("Insurance", "United Kingdom");

        ScoredMatch m = CompanyScore.score(r, f);
        // adjacency weight 0.25 over primary denominator 0.50 → 50.
        assertThat(m.matchScore()).isEqualTo(50);
        assertThat(m.relevanceType()).isEqualTo("Adjacent");
        assertThat(m.breakdown().adjacentSector()).isTrue();
    }

    @Test
    void adjacentOnlyFilter_adjacentHit_scores100() {
        var f = filter(List.of(), List.of("Insurance"), List.of(), List.of(), List.of(), List.of(), null);
        var r = plain("Insurance", "Qatar");

        ScoredMatch m = CompanyScore.score(r, f);
        assertThat(m.matchScore()).isEqualTo(100);   // 0.25 / 0.25
        assertThat(m.relevanceType()).isEqualTo("Adjacent");
    }

    @Test
    void subTagOverlap_drivesAiInferred_whenNoSectorHit() {
        var f = filter(List.of(), List.of(), List.of("fintech-payments", "fintech-neobank"),
            List.of(), List.of(), List.of(), null);
        var hit = row("Retail & E-Commerce", List.of(), List.of(), List.of("fintech-payments"),
            "United States", null, null, null);
        var miss = row("Retail & E-Commerce", List.of(), List.of(), List.of("luxury-retail"),
            "United States", null, null, null);

        assertThat(CompanyScore.score(hit, f).matchScore()).isEqualTo(100);   // 0.25 / 0.25
        assertThat(CompanyScore.score(hit, f).relevanceType()).isEqualTo("AI Inferred");
        assertThat(CompanyScore.score(miss, f).matchScore()).isEqualTo(0);
    }

    @Test
    void revenueAndEmployee_softSignals_raiseDenominatorAndNumerator() {
        // Requested: primary(0.5) + revenue(0.08). Row matches primary only.
        var f = filter(List.of("Technology & Software"), List.of(), List.of(),
            List.of(), List.of("$1-10B"), List.of(), null);
        var r = row("Technology & Software", List.of(), List.of(), List.of(),
            "United States", "$50-250M", null, null);

        ScoredMatch m = CompanyScore.score(r, f);
        // 0.5 / (0.5 + 0.08) = 0.8621 → 86.
        assertThat(m.matchScore()).isEqualTo(86);
        assertThat(m.breakdown().revenueBand()).isFalse();
    }

    @Test
    void isListed_countsOnlyWhenFilterSetAndEqual() {
        var f = filter(List.of("Telecommunications"), List.of(), List.of(), List.of(), List.of(), List.of(), true);
        var listed = row("Telecommunications", List.of(), List.of(), List.of(), "France", null, null, true);
        var notListed = row("Telecommunications", List.of(), List.of(), List.of(), "France", null, null, false);

        assertThat(CompanyScore.score(listed, f).matchScore()).isEqualTo(100);   // (0.5+0.04)/(0.5+0.04)
        assertThat(CompanyScore.score(listed, f).breakdown().isListed()).isTrue();
        // Listing requested but not matched: 0.5/0.54 = 92.59 → 93.
        assertThat(CompanyScore.score(notListed, f).matchScore()).isEqualTo(93);
        assertThat(CompanyScore.score(notListed, f).breakdown().isListed()).isFalse();
    }

    @Test
    void emptyFilter_scoresZero_AiInferred() {
        var f = filter(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        var r = row("Technology & Software", List.of("Technology & Software"),
            List.of(new SectorWeight("Technology & Software", "dominant")),
            List.of("enterprise-saas"), "United States", "$1-10B", "1k-5k", true);

        ScoredMatch m = CompanyScore.score(r, f);
        assertThat(m.matchScore()).isEqualTo(0);
        assertThat(m.relevanceType()).isEqualTo("AI Inferred");
    }
}
