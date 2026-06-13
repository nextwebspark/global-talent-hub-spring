package com.globaltalenthub.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Company payload emitted on the {@code company_enriched} SSE event. Mirrors the
 * streamCompany object in seedListSearch.ts consumed by useSearchStream.ts.
 */
public record StreamCompanyDto(
    Long id,
    String name,
    String sector,
    String country,
    String geography,
    String revenue,
    Integer employees,
    String website,
    String summary,
    String latitude,
    String longitude,
    String relevanceType,
    String relevanceRationale,
    Integer confidenceScore,
    boolean isUserAccepted,
    boolean isUserRejected,
    List<Object> executives,
    boolean isNew
) {}
