package com.globaltalenthub.service.pipeline;

import java.math.BigDecimal;
import java.util.List;

/**
 * A row from the company_enrichment table — the 23-column projection selected by
 * {@code CompanyEnrichmentRepository.queryCandidatePool}. Self-contained: carries
 * companyName / slug / country directly, no join. Mirrors EnrichedCompanyRow in
 * server/storage/types.ts.
 */
public record EnrichedRow(
    Long id,
    String companyId,
    String companyName,
    String slug,
    String country,
    String primarySector,
    List<String> sectorTags,
    List<String> subTags,
    List<String> keywords,
    String tagline,
    String businessDescription,
    String employeeBand,
    Integer employeeCountEstimate,
    String revenueBand,
    Long revenueEstimateUsd,
    Boolean isListed,
    String hqCity,
    // 0-1 data QUALITY of the enrichment record — used only as a sort tie-breaker,
    // distinct from companies.confidence (1-10) derived from the match score.
    BigDecimal confidence,
    String website,
    String phone,
    String email,
    String address
) {
    CompanyScore.ScorableRow toScorable() {
        return new CompanyScore.ScorableRow(primarySector, subTags, country, revenueBand, employeeBand, isListed);
    }
}
