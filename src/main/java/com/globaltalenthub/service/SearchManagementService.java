package com.globaltalenthub.service;

import com.globaltalenthub.entity.Company;
import com.globaltalenthub.entity.Executive;
import com.globaltalenthub.entity.SearchQuery;
import com.globaltalenthub.repository.CompanyRepository;
import com.globaltalenthub.repository.ExecutiveRepository;
import com.globaltalenthub.repository.SearchQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Search-query management: history, full results (with coordinate fallback),
 * saved-view persistence, and add-to-project promotion. Port of the non-SSE parts
 * of search.ts + the search-query helpers in DatabaseStorage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchManagementService {

    private final SearchQueryRepository searchQueryRepo;
    private final CompanyRepository companyRepo;
    private final ExecutiveRepository executiveRepo;
    private final CoordinateFallbackService coordinateFallbackService;

    public record HistoryEntry(SearchQuery searchQuery, long companyCount) {}

    public record CompanyWithExecs(Company company, List<Executive> executives) {}

    public record FullResults(SearchQuery searchQuery, List<CompanyWithExecs> companies) {}

    public record AddToProjectResult(Long searchQueryId, String query, int companiesAdded, int executivesAdded) {}

    public List<HistoryEntry> history(String orgId) {
        return searchQueryRepo.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
            .map(q -> new HistoryEntry(q, companyRepo.findBySearchQueryIdAndOrgId(q.getId(), orgId).size()))
            .limit(50)
            .toList();
    }

    /** Full results with coordinate fallback applied per company (mirrors search.ts). */
    public FullResults fullResults(Long searchQueryId, String orgId) {
        SearchQuery sq = searchQueryRepo.findByIdAndOrgId(searchQueryId, orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Search results not found"));
        List<CompanyWithExecs> companies = new ArrayList<>();
        for (Company c : companyRepo.findBySearchQueryIdAndOrgId(searchQueryId, orgId)) {
            CoordinateFallbackService.Result coord =
                coordinateFallbackService.apply(c.getRegion(), c.getCountry(), c.getLatitude(), c.getLongitude());
            c.setLatitude(coord.latitude());
            c.setLongitude(coord.longitude());
            companies.add(new CompanyWithExecs(c, executiveRepo.findByCompanyIdAndOrgId(c.getId(), orgId)));
        }
        return new FullResults(sq, companies);
    }

    @Transactional
    public void saveSatelliteHierarchies(Long id, Map<String, Object> data, String orgId) {
        SearchQuery sq = require(id, orgId);
        sq.setSatelliteHierarchies(data);
        searchQueryRepo.save(sq);
    }

    @Transactional
    public void saveSatelliteOrders(Long id, Map<String, Object> data, String orgId) {
        SearchQuery sq = require(id, orgId);
        sq.setSatelliteOrders(data);
        searchQueryRepo.save(sq);
    }

    @Transactional
    public void saveMapPositions(Long id, Map<String, Object> data, String orgId) {
        SearchQuery sq = require(id, orgId);
        sq.setMapPositions(data);
        searchQueryRepo.save(sq);
    }

    @Transactional
    public void saveTableConfig(Long id, Map<String, Object> data, String orgId) {
        SearchQuery sq = require(id, orgId);
        sq.setTableConfig(data);
        searchQueryRepo.save(sq);
    }

    /**
     * Promote a draft to active with only the company IDs owned by the session,
     * re-affirming their search_query_id. Mirrors add-to-project in search.ts.
     */
    @Transactional
    public AddToProjectResult addToProject(List<Long> companyIds, String sessionId, Long searchQueryId, String orgId) {
        if (companyIds == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "companyIds array is required");
        }
        if (sessionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required for ownership validation");
        }
        if (searchQueryId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "searchQueryId is required to promote the draft");
        }
        List<Company> owned = companyIds.isEmpty()
            ? List.of()
            : companyRepo.findByIdInAndSearchSessionIdAndOrgId(companyIds, sessionId, orgId);
        if (owned.size() != companyIds.size()) {
            log.warn("[add-to-project] {} company IDs rejected (not owned by session {})",
                companyIds.size() - owned.size(), sessionId);
        }
        if (owned.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No valid companies found for the provided session");
        }

        SearchQuery sq = require(searchQueryId, orgId);
        sq.setStatus("active");
        sq.setResultCount(owned.size());
        searchQueryRepo.save(sq);

        int totalExecutives = 0;
        for (Company c : owned) {
            c.setSearchQueryId(sq.getId());
            companyRepo.save(c);
            totalExecutives += executiveRepo.findByCompanyIdAndOrgId(c.getId(), orgId).size();
        }
        return new AddToProjectResult(sq.getId(), sq.getQuery(), owned.size(), totalExecutives);
    }

    private SearchQuery require(Long id, String orgId) {
        return searchQueryRepo.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Search query not found"));
    }
}
