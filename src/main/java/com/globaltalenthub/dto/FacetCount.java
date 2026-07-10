package com.globaltalenthub.dto;

/** One facet bucket: a filter value + how many companies carry it. */
public record FacetCount(String value, long count) {}
