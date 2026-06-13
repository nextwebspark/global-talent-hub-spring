package com.globaltalenthub.dto;

import java.util.List;

/**
 * Intent payload emitted on the {@code intent_extracted} SSE event and persisted
 * to the search session. Mirrors filterToInferredIntent in seedListSearch.ts and
 * the InferredIntent shape consumed by client/src/lib/useSearchStream.ts.
 */
public record InferredIntentDto(
    List<String> primarySectors,
    List<String> adjacentSectors,
    List<String> inferredSectors,
    List<String> targetGeographies,
    String commercialRole,
    String searchRationale,
    double confidenceScore,
    List<String> keyInclusions,
    List<String> keyExclusions
) {}
