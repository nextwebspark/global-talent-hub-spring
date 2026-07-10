package com.globaltalenthub.dto;

import java.util.List;

/**
 * The wizard/LLM criteria for a search run. One object holds both the
 * <b>query-active</b> fields (used by phase-01 {@code /search}) and the
 * <b>captured-but-unsearched</b> people fields (stored only — no employee data yet).
 *
 * <p>All lists may be null; treat null as empty. Serialized into the run's
 * {@code parsed_criteria} jsonb.
 */
public record SearchCriteria(
    // query-active → phase-01 /search
    List<String> industry,
    List<String> country,
    List<String> revenueRange,
    List<String> employeeRange,
    // captured, STORED ONLY, ignored by search (future: employee data)
    List<String> positions,
    List<String> seniority,
    List<String> experience
) {
    public static SearchCriteria empty() {
        return new SearchCriteria(null, null, null, null, null, null, null);
    }

    /**
     * Overlay {@code client} on top of {@code base} (the LLM parse): for each field the
     * client's non-empty list wins, else the base is kept. People fields come only from
     * the client (never LLM-derived).
     */
    public static SearchCriteria merge(SearchCriteria base, SearchCriteria client) {
        if (client == null) return base == null ? empty() : base;
        if (base == null) base = empty();
        return new SearchCriteria(
            pick(client.industry, base.industry),
            pick(client.country, base.country),
            pick(client.revenueRange, base.revenueRange),
            pick(client.employeeRange, base.employeeRange),
            client.positions,
            client.seniority,
            client.experience
        );
    }

    private static List<String> pick(List<String> preferred, List<String> fallback) {
        return preferred != null && !preferred.isEmpty() ? preferred : fallback;
    }
}
