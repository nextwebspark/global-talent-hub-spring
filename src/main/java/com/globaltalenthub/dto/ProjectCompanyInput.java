package com.globaltalenthub.dto;

import java.math.BigDecimal;

/** One universe member on project create. */
public record ProjectCompanyInput(
    Long companyId,
    String relevanceType,
    Integer confidence,
    BigDecimal mapX,
    BigDecimal mapY
) {}
