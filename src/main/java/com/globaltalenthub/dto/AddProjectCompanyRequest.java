package com.globaltalenthub.dto;

/**
 * Add one catalog company to a project's universe on demand (the sourcing "Add to universe /
 * shortlist / decline" action). {@code companyId} is required; {@code status} defaults to
 * {@code in_universe}. {@code relevanceType}/{@code confidence} are optional metadata.
 */
public record AddProjectCompanyRequest(
    Long companyId,
    String status,
    String relevanceType,
    Integer confidence
) {}
