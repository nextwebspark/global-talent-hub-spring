package com.globaltalenthub.dto;

import java.time.LocalDateTime;

/**
 * Search-run view for the client. {@code parsedCriteria} is the merged result
 * (LLM parse + client edits) as stored in the run's jsonb.
 */
public record SearchRunDto(
    Long id,
    String query,
    String mode,
    SearchCriteria parsedCriteria,
    Integer resultCount,
    String status,
    LocalDateTime createdAt
) {}
