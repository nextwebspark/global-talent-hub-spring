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
    String searchRationale
) {
    /** Empty filter used on LLM/parse failure — no over-filtering, never throws. */
    public static EnrichmentFilter empty(String query) {
        return new EnrichmentFilter(
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            null, "Companies relevant to: " + query);
    }
}
