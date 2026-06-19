package com.globaltalenthub.service.pipeline;

import java.util.List;

/**
 * Vocabulary-validated filter extracted from a user query.
 * Port of EnrichmentFilter in server/services/pipeline/enrichmentFilter.ts.
 *
 * <p>All array fields are pre-validated against the controlled taxonomy by
 * {@link EnrichmentFilterService}. {@code adjacentSectors} is derived from the
 * adjacency map, never user-supplied.
 */
public record EnrichmentFilter(
    List<String> primarySectors,
    List<String> adjacentSectors,
    List<String> subTags,
    List<String> countries,
    List<String> employeeBands,
    List<String> revenueBands,
    Boolean isListed,
    String searchRationale,
    // Result count the classifier inferred from the query, already clamped to the
    // configured [min,max] (or the configured default when the query implies none).
    int limit
) {
    static final String FALLBACK_RATIONALE_PREFIX = "Companies relevant to: ";

    /** Empty filter used on LLM/parse failure — no over-filtering, never throws. */
    public static EnrichmentFilter empty(String query, int limit) {
        return new EnrichmentFilter(
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            null, FALLBACK_RATIONALE_PREFIX + query, limit);
    }
}
