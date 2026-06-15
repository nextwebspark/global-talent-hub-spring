package com.globaltalenthub.service;

import java.util.UUID;

import com.globaltalenthub.entity.Company;
import com.globaltalenthub.entity.SearchQuery;
import com.globaltalenthub.repository.CompanyRepository;
import com.globaltalenthub.repository.SearchQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch enrichment for a project's companies. Port of searchEnrich.ts.
 *
 * <p>Currently performs the LLM-backed sector backfill (companies missing a sector)
 * and returns the refreshed company set. The deep revenue/executive web-enrichment
 * ({@code enrichSearchResults}) depends on the Serper/Gemini search adapter and is a
 * documented follow-up — it requires live search credentials and is not yet ported.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchEnrichService {

    private final SearchQueryRepository searchQueryRepo;
    private final CompanyRepository companyRepo;
    private final SectorService sectorService;

    @Transactional
    public Map<String, Object> enrichAll(Long searchQueryId, UUID orgId) {
        SearchQuery sq = searchQueryRepo.findByIdAndOrgId(searchQueryId, orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Search not found"));

        List<Company> companies = companyRepo.findBySearchQueryIdAndOrgId(searchQueryId, orgId);
        int sectorsInferred = 0;
        for (Company c : companies) {
            if (c.getSector() == null || c.getSector().isBlank()) {
                SectorService.SectorResult sr = sectorService.inferSector(c.getName());
                if (sr.sector() != null) {
                    c.setSector(sr.sector());
                    c.setSectorCategory(sr.category());
                    companyRepo.save(c);
                    sectorsInferred++;
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("searchQuery", sq);
        out.put("enrichment", Map.of("sectorsInferred", sectorsInferred));
        out.put("companies", companyRepo.findBySearchQueryIdAndOrgId(searchQueryId, orgId));
        return out;
    }
}
