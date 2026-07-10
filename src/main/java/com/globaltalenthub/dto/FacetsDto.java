package com.globaltalenthub.dto;

import java.util.List;

/** Grouped filter counts for the scope sidebar (global over the whole catalog). */
public record FacetsDto(
    List<FacetCount> industries,
    List<FacetCount> countries,
    List<FacetCount> revenueRanges,
    List<FacetCount> employeeRanges
) {}
