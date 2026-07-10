package com.globaltalenthub.dto;

import java.time.LocalDateTime;

/** Row shape for the projects list. */
public record ProjectSummaryDto(
    Long id,
    String name,
    Long clientId,
    String clientName,
    long companyCount,
    String status,
    LocalDateTime createdAt
) {}
