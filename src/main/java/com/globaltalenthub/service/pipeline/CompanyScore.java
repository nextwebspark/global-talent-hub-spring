package com.globaltalenthub.service.pipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, deterministic match scoring for enriched-company rows.
 * Port of server/services/pipeline/companyScore.ts — no I/O, no DB client.
 *
 * <p>A row surfaces on any <em>crucial</em> signal (primary sector, adjacent
 * sector, or sub-tag overlap) and is scored on how many of the query's
 * dimensions it satisfies. <em>Soft</em> signals (country, revenue band,
 * employee band, listing status) only raise the score — never exclude a row.
 * A dimension the query did not request never enters the denominator.
 */
public final class CompanyScore {

    private CompanyScore() {}

    // Crucial signals.
    private static final double W_PRIMARY_SECTOR = 0.5;
    private static final double W_ADJACENT_SECTOR = 0.25;
    private static final double W_SUB_TAGS = 0.25;

    // Soft signals — boost only.
    private static final double W_COUNTRY = 0.12;
    private static final double W_REVENUE_BAND = 0.08;
    private static final double W_EMPLOYEE_BAND = 0.08;
    private static final double W_IS_LISTED = 0.04;

    /** Fields scoreCompany reads off an enriched-company row. */
    public record ScorableRow(
        String primarySector,
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

    /**
     * Score a row against a vocabulary-validated filter. matchScore is the
     * weighted fraction of the requested dimensions the row satisfies, 0..100.
     */
    public static ScoredMatch score(ScorableRow row, EnrichmentFilter filter) {
        boolean primarySector = filter.primarySectors().contains(row.primarySector());
        // Adjacent only counts when not already a primary hit (precedence: primary > adjacent).
        boolean adjacentSector = !primarySector && filter.adjacentSectors().contains(row.primarySector());
        boolean subTags = arraysOverlap(row.subTags(), filter.subTags());
        boolean country = filter.countries().contains(row.country());
        boolean revenueBand = row.revenueBand() != null && filter.revenueBands().contains(row.revenueBand());
        boolean employeeBand = row.employeeBand() != null && filter.employeeBands().contains(row.employeeBand());
        boolean isListed = filter.isListed() != null && filter.isListed().equals(row.isListed());

        double sectorRaw = primarySector ? W_PRIMARY_SECTOR : adjacentSector ? W_ADJACENT_SECTOR : 0;
        double sectorMax = !filter.primarySectors().isEmpty()
            ? W_PRIMARY_SECTOR
            : !filter.adjacentSectors().isEmpty()
                ? W_ADJACENT_SECTOR
                : 0;

        double rawScore = sectorRaw;
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

        String relevanceType = primarySector ? "Direct" : adjacentSector ? "Adjacent" : "AI Inferred";

        return new ScoredMatch(matchScore, relevanceType,
            new MatchBreakdown(primarySector, adjacentSector, subTags, country, revenueBand, employeeBand, isListed));
    }
}
