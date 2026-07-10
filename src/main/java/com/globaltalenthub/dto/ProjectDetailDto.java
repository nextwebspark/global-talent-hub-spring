package com.globaltalenthub.dto;

import java.util.List;

/** Full project view: client (resolved) + the universe rows. */
public record ProjectDetailDto(
    Long id,
    String name,
    String status,
    Long searchRunId,
    ClientDto client,
    List<ProjectCompanyDto> companies
) {}
