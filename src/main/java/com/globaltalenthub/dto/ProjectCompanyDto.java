package com.globaltalenthub.dto;

import java.math.BigDecimal;

/** A universe row joined to its master company display fields. */
public record ProjectCompanyDto(
    Long id,
    Long companyId,
    String name,
    String logo,
    String primaryIndustry,
    String hqCountry,
    String hqCity,
    Long revenueUsd,
    String revenueRange,
    Integer employeeCount,
    String employeeRange,
    String orgType,
    String ownership,
    Integer founded,
    String website,
    String linkedinUrl,
    String description,
    String[] industryTags,
    String[] specialties,
    String relevanceType,
    Integer confidence,
    String status,
    BigDecimal mapX,
    BigDecimal mapY
) {}
