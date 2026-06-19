package com.globaltalenthub.service.pipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, deterministic match scoring for enriched-company rows.
 * Port of server/services/pipeline/companyScore.ts — no I/O, no DB client.
 *
 * <p>A row surfaces on any <em>crucial</em> signal (primary sector, a weighted
 * secondary sector from {@code sector_mix}, an adjacency-map sector, a
 * {@code sector_tags} overlap, or a sub-tag overlap) and is scored on how many of
 * the query's dimensions it satisfies. <em>Soft</em> signals (country, revenue
 * band, employee band, listing status) only raise the score — never exclude a row.
 * A dimension the query did not request never enters the denominator.
 */
public final class CompanyScore {

    private CompanyScore() {}

    // Crucial sector ladder — best single signal wins (no double-count).
    // dominant == primary_sector by the enrichment invariant, so it is covered by
    // the primary branch and not handled separately here.
    private static final double W_PRIMARY_SECTOR = 0.5;   // primary_sector match (Direct)
    private static final double W_SECTOR_SIGNIFICANT = 0.35; // sector_mix "significant" (Adjacent)
    private static final double W_ADJACENT_SECTOR = 0.25; // adjacency-map sector (Adjacent)
    private static final double W_SECTOR_MINOR = 0.2;     // sector_mix "minor" (AI Inferred)
    private static final double W_SECTOR_TAGS = 0.15;     // sector_tags overlap (AI Inferred)
    private static final double W_SUB_TAGS = 0.25;        // sub_tags overlap

    // Country is a strong differentiator: when a country is requested, a matching-country
    // row should clearly outrank a same-sector row in the wrong country, while the
    // wrong-country row still scores > 0 (low confidence, never excluded).
    private static final double W_COUNTRY = 0.5;

    // Remaining soft signals — small boosts only.
    private static final double W_REVENUE_BAND = 0.08;
    private static final double W_EMPLOYEE_BAND = 0.08;
    private static final double W_IS_LISTED = 0.04;

    private static final String SIGNIFICANT = "significant";
    private static final String MINOR = "minor";

    /** Fields scoreCompany reads off an enriched-company row. */
    public record ScorableRow(
        String primarySector,
        List<String> sectorTags,
        List<EnrichedRow.SectorWeight> sectorMix,
        List<String> subTags,
        String country,
        String revenueBand,
        String employeeBand,
        Boolean isListed
    ) {}

    public record MatchBreakdown(
        boolean primarySector,
        boolean adjacentSector,
        boolean subTags,
        boolean country,
        boolean revenueBand,
        boolean employeeBand,
        boolean isListed
    ) {}

    public record ScoredMatch(
        int matchScore,            // 0..100 integer (percentage)
        String relevanceType,      // "Direct" | "Adjacent" | "AI Inferred"
        MatchBreakdown breakdown
    ) {}

    private static boolean arraysOverlap(List<String> a, List<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        Set<String> set = new HashSet<>(a);
        return b.stream().anyMatch(set::contains);
    }

    /** Resolved sector signal for a row: the single strongest match and its relevance tier. */
    private record SectorSignal(double weight, String relevanceType, boolean primary, boolean adjacent) {}

    /**
     * Pick the single strongest sector signal for a row against the requested sectors,
     * in priority order: primary → sector_mix significant → adjacency map → sector_mix
     * minor → sector_tags overlap. Never combines signals.
     */
    private static SectorSignal resolveSector(ScorableRow row, EnrichmentFilter filter) {
        if (filter.primarySectors().contains(row.primarySector())) {
            return new SectorSignal(W_PRIMARY_SECTOR, "Direct", true, false);
        }
        if (sectorMixHas(row, filter.primarySectors(), SIGNIFICANT)) {
            return new SectorSignal(W_SECTOR_SIGNIFICANT, "Adjacent", false, true);
        }
        if (filter.adjacentSectors().contains(row.primarySector())) {
            return new SectorSignal(W_ADJACENT_SECTOR, "Adjacent", false, true);
        }
        if (sectorMixHas(row, filter.primarySectors(), MINOR)) {
            return new SectorSignal(W_SECTOR_MINOR, "AI Inferred", false, false);
        }
        if (arraysOverlap(row.sectorTags(), filter.primarySectors())) {
            return new SectorSignal(W_SECTOR_TAGS, "AI Inferred", false, false);
        }
        return new SectorSignal(0, "AI Inferred", false, false);
    }

    private static boolean sectorMixHas(ScorableRow row, List<String> requested, String weight) {
        if (row.sectorMix() == null || row.sectorMix().isEmpty() || requested.isEmpty()) return false;
        Set<String> want = new HashSet<>(requested);
        return row.sectorMix().stream()
            .anyMatch(sw -> weight.equals(sw.weight()) && want.contains(sw.sector()));
    }

    /**
     * Score a row against a vocabulary-validated filter. matchScore is the
     * weighted fraction of the requested dimensions the row satisfies, 0..100.
     */
    public static ScoredMatch score(ScorableRow row, EnrichmentFilter filter) {
        SectorSignal sector = resolveSector(row, filter);

        boolean subTags = arraysOverlap(row.subTags(), filter.subTags());
        boolean country = filter.countries().contains(row.country());
        boolean revenueBand = row.revenueBand() != null && filter.revenueBands().contains(row.revenueBand());
        boolean employeeBand = row.employeeBand() != null && filter.employeeBands().contains(row.employeeBand());
        boolean isListed = filter.isListed() != null && filter.isListed().equals(row.isListed());

        // The sector denominator is the strongest tier the query could have matched:
        // primary weight if any primary sector was requested, else the adjacency weight.
        double sectorMax = !filter.primarySectors().isEmpty()
            ? W_PRIMARY_SECTOR
            : !filter.adjacentSectors().isEmpty()
                ? W_ADJACENT_SECTOR
                : 0;

        double rawScore = sector.weight();
        double maxScore = sectorMax;

        if (!filter.subTags().isEmpty()) {
            maxScore += W_SUB_TAGS;
            if (subTags) rawScore += W_SUB_TAGS;
        }
        if (!filter.countries().isEmpty()) {
            maxScore += W_COUNTRY;
            if (country) rawScore += W_COUNTRY;
        }
        if (!filter.revenueBands().isEmpty()) {
            maxScore += W_REVENUE_BAND;
            if (revenueBand) rawScore += W_REVENUE_BAND;
        }
        if (!filter.employeeBands().isEmpty()) {
            maxScore += W_EMPLOYEE_BAND;
            if (employeeBand) rawScore += W_EMPLOYEE_BAND;
        }
        if (filter.isListed() != null) {
            maxScore += W_IS_LISTED;
            if (isListed) rawScore += W_IS_LISTED;
        }

        double fraction = maxScore > 0 ? rawScore / maxScore : 0;
        int matchScore = (int) Math.round(fraction * 100);

        return new ScoredMatch(matchScore, sector.relevanceType(),
            new MatchBreakdown(sector.primary(), sector.adjacent(), subTags, country, revenueBand, employeeBand, isListed));
    }
}
