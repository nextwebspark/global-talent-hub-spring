package com.globaltalenthub.dto;

import java.util.List;

/** Confirm-universe payload: name + client (existing or new) + originating run + members. */
public record CreateProjectRequest(
    String name,
    ClientRef client,
    Long searchRunId,
    List<ProjectCompanyInput> companies
) {}
