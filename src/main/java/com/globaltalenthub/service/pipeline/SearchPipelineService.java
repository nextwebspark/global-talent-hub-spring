package com.globaltalenthub.service.pipeline;

import java.util.UUID;

import com.globaltalenthub.dto.StreamCompanyDto;
import com.globaltalenthub.entity.Company;
import com.globaltalenthub.repository.SearchQueryRepository;
import com.globaltalenthub.service.CompanyService;
import com.globaltalenthub.service.CoordinateFallbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * SSE search orchestration — port of runSeedListEnhancedStream (seedListSearch.ts).
 * Emits the event sequence consumed by useSearchStream.ts via an {@link EventSink}
 * (the Java analogue of the Node async generator's yields).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchPipelineService {

    // Per-field confidence (1–10 scale, see CompanyService) the seed pipeline asserts for
    // country/sector: 7 = trusted enrichment-sourced, beats default 5 but below manual edits.
    private static final Map<String, Integer> FIELD_CONFIDENCES = Map.of("country", 7, "sector", 7);
    private static final String NO_RESULTS_REASON =
        "Could not identify relevant sectors for this query. Try describing an industry, geography, "
        + "or company type (e.g. 'mid-size pharma companies in Southeast Asia').";

    private final EnrichmentFilterService enrichmentFilterService;
    private final CompanyEnrichmentQueryService enrichmentQueryService;
    private final CompanyService companyService;
    private final CoordinateFallbackService coordinateFallbackService;
    private final SearchQueryRepository searchQueryRepository;

    public void runSeedListEnhancedStream(String query, Long searchQueryId, UUID orgId, int limit,
                                          BooleanSupplier aborted, String sessionId, String briefContext,
                                          EventSink sink) {
        sink.emit("status", "Understanding your query...", null);

        EnrichmentFilter filter = enrichmentFilterService.extract(query, briefContext);
        if (!emitIntent(filter, query, searchQueryId, sink)) return;

        if (aborted.getAsBoolean()) return;
        sink.emit("status", "Querying enriched companies...", null);

        List<EnrichedCompanyMatch> rows = fetchRows(filter, limit, searchQueryId, sink);
        if (rows == null) return;

        emitCompaniesFound(rows, aborted, sink);
        if (aborted.getAsBoolean()) return;

        int persistedCount = persistAndEmit(rows, filter, searchQueryId, orgId, sessionId, aborted, sink);

        try {
            searchQueryRepository.updateResultCount(searchQueryId, persistedCount, orgId);
        } catch (Exception e) {
            log.warn("[EnrichedSearch] updateResultCount failed: {}", e.getMessage());
        }
        sink.emit("search_complete", "Search complete",
            Map.of("totalCompanies", persistedCount, "searchQueryId", searchQueryId));
    }

    /**
     * Emits intent_extracted (and adjacent_sector_found if applicable).
     * Returns false if the filter is unmapped and a no_results event was emitted instead.
     */
    private boolean emitIntent(EnrichmentFilter filter, String query, Long searchQueryId, EventSink sink) {
        String sectorLabel = filter.primarySectors().isEmpty() ? "any" : String.join(", ", filter.primarySectors());
        sink.emit("intent_extracted", "Sectors: " + sectorLabel,
            Map.of("intent", EnrichmentFilterService.filterToInferredIntent(filter)));

        if (EnrichmentFilterService.isUnmapped(filter, query)) {
            sink.emit("no_results", "No relevant sectors identified", Map.of(
                "totalCompanies", 0,
                "searchQueryId", searchQueryId,
                "noResultsReason", NO_RESULTS_REASON));
            return false;
        }

        if (!filter.adjacentSectors().isEmpty()) {
            sink.emit("adjacent_sector_found",
                "AI suggests " + filter.adjacentSectors().size() + " adjacent sectors",
                Map.of("adjacentSectors", filter.adjacentSectors()));
        }
        return true;
    }

    /**
     * Queries enriched rows. Returns null on DB error (error event already emitted)
     * or empty-result (search_complete already emitted).
     */
    private List<EnrichedCompanyMatch> fetchRows(EnrichmentFilter filter, int limit, Long searchQueryId, EventSink sink) {
        List<EnrichedCompanyMatch> rows;
        try {
            rows = enrichmentQueryService.query(filter, limit);
            log.info("[EnrichedSearch] Fetched {} enriched rows", rows.size());
        } catch (Exception e) {
            log.error("[EnrichedSearch] Query failed: {}", e.getMessage());
            sink.emit("error", "Failed to load companies: " + e.getMessage(), null);
            return null;
        }

        if (rows.isEmpty()) {
            sink.emit("search_complete", "No matching companies found",
                Map.of("totalCompanies", 0, "searchQueryId", searchQueryId));
            return null;
        }
        return rows;
    }

    /** Emits a company_found event for each match. */
    private void emitCompaniesFound(List<EnrichedCompanyMatch> rows, BooleanSupplier aborted, EventSink sink) {
        for (EnrichedCompanyMatch match : rows) {
            if (aborted.getAsBoolean()) return;
            EnrichedRow row = match.row();
            sink.emit("company_found", "Found: " + row.companyName(), Map.of(
                "companyName", safe(row.companyName()),
                "name", safe(row.companyName()),
                "sector", safe(row.primarySector()),
                "relevanceType", match.relevanceType()));
        }
    }

    /** Upserts each match and emits company_enriched. Returns count of persisted companies. */
    private int persistAndEmit(List<EnrichedCompanyMatch> rows, EnrichmentFilter filter,
                                Long searchQueryId, UUID orgId, String sessionId,
                                BooleanSupplier aborted, EventSink sink) {
        int count = 0;
        for (EnrichedCompanyMatch match : rows) {
            if (aborted.getAsBoolean()) return count;
            try {
                String rationale = relevanceRationale(match, filter);
                Company companyData = mapToCompany(match, rationale, sessionId);
                CompanyService.UpsertResult result =
                    companyService.upsertNonDestructive(companyData, searchQueryId, orgId, FIELD_CONFIDENCES);
                count++;
                StreamCompanyDto dto = buildStreamDto(match, result, rationale);
                sink.emit("company_enriched", "Classified: " + result.company().getName(), Map.of("company", dto));
            } catch (Exception e) {
                log.error("[EnrichedSearch] Failed to persist \"{}\": {}", match.row().companyName(), e.getMessage());
            }
        }
        return count;
    }

    private StreamCompanyDto buildStreamDto(EnrichedCompanyMatch match, CompanyService.UpsertResult result, String rationale) {
        Company c = result.company();
        EnrichedRow row = match.row();
        return new StreamCompanyDto(
            c.getId(), c.getName(), c.getSector(), c.getCountry(),
            c.getRegion() != null ? c.getRegion() : c.getCountry(),
            c.getRevenue() != null ? c.getRevenue().toPlainString() : revenueValue(row),
            c.getEmployees(),
            c.getWebsite() != null ? c.getWebsite() : row.website(),
            c.getSummary() != null ? c.getSummary() : row.businessDescription(),
            c.getLatitude() != null ? c.getLatitude().toPlainString() : null,
            c.getLongitude() != null ? c.getLongitude().toPlainString() : null,
            match.relevanceType(), rationale, match.matchScore(),
            false, false, new ArrayList<>(), result.isNew());
    }

    // Build a Company from an enriched row — port of enrichedRowToInsertCompany.
    private Company mapToCompany(EnrichedCompanyMatch match, String rationale, String sessionId) {
        EnrichedRow row = match.row();
        CoordinateFallbackService.Result coord =
            coordinateFallbackService.apply(row.hqCity(), row.country(), null, null);

        Company c = new Company();
        c.setName(row.companyName());
        c.setSector(row.primarySector());
        c.setCountry(row.country());
        c.setRegion(row.hqCity());
        c.setStreetAddress(row.address());
        c.setLatitude(coord.latitude());
        c.setLongitude(coord.longitude());
        c.setLocationPrecision(coord.locationPrecision());
        if (row.revenueEstimateUsd() != null) {
            c.setRevenue(BigDecimal.valueOf(row.revenueEstimateUsd()));
        }
        c.setRevenueRange(row.revenueBand());
        c.setRevenueCurrency("USD");
        c.setEmployees(row.employeeCountEstimate());
        c.setCompanySize(row.employeeBand());
        c.setWebsite(row.website());
        c.setSummary(row.businessDescription() != null ? row.businessDescription() : row.tagline());
        // matchScore (0-100) persisted raw on confidence_score; companies.confidence is /10.
        c.setConfidenceScore(match.matchScore());
        c.setConfidence(Math.max(1, Math.min(10, (int) Math.round(match.matchScore() / 10.0))));
        c.setRelevanceType(match.relevanceType());
        c.setRelevanceRationale(rationale);
        c.setSearchSessionId(sessionId);
        return c;
    }

    private static String revenueValue(EnrichedRow row) {
        if (row.revenueEstimateUsd() != null) return String.valueOf(row.revenueEstimateUsd());
        return row.revenueBand();
    }

    // Port of relevanceRationale(row, filter) — lead by relevance type + soft signals.
    static String relevanceRationale(EnrichedCompanyMatch match, EnrichmentFilter filter) {
        CompanyScore.MatchBreakdown b = match.scored().breakdown();
        String lead;
        if ("Direct".equals(match.relevanceType())) {
            lead = "Primary sector match (" + match.row().primarySector() + ")";
        } else if ("Adjacent".equals(match.relevanceType())) {
            lead = "Adjacent sector (" + match.row().primarySector() + ") to the target";
        } else {
            lead = "Matched on specialism tags";
        }

        List<String> soft = new ArrayList<>();
        if (b.country()) soft.add("in target geography");
        if (b.employeeBand()) soft.add("matching size band");
        if (b.revenueBand()) soft.add("matching revenue band");
        if (b.isListed()) soft.add("listing status matches");
        String softStr = soft.isEmpty() ? "" : ", " + String.join(", ", soft);

        return lead + softStr + ". " + filter.searchRationale();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
