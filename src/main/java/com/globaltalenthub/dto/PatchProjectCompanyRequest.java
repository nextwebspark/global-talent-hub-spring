package com.globaltalenthub.dto;

import java.math.BigDecimal;

/**
 * Partial update of one project-company row — only non-null fields are applied.
 * {@code status} ∈ untriaged|in_universe|shortlisted|declined; {@code relevanceType} ∈
 * Direct|Adjacent|AI Inferred (both validated against the allowed set → 400 on unknown).
 */
public record PatchProjectCompanyRequest(
    String status,
    String relevanceType,
    BigDecimal mapX,
    BigDecimal mapY
) {}
