package com.globaltalenthub.dto;

/**
 * Create-a-search-run payload. {@code query} is the raw wizard prompt (required for
 * {@code Search}); {@code criteria} is the OPTIONAL wizard-captured/edited criteria —
 * when present it is merged over the LLM parse (client wins per field).
 */
public record CreateSearchRunRequest(
    String query,
    String mode,
    SearchCriteria criteria
) {}
