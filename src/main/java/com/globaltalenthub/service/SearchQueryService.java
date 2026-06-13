package com.globaltalenthub.service;

import com.globaltalenthub.entity.Company;
import com.globaltalenthub.entity.SearchQuery;
import com.globaltalenthub.repository.CompanyRepository;
import com.globaltalenthub.repository.SearchQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

/**
 * Thin service over SearchQueryRepository — mirrors the DatabaseStorage search-query
 * methods the SSE flow and project-management endpoints need.
 */
@Service
@RequiredArgsConstructor
public class SearchQueryService {

    private final SearchQueryRepository repository;
    private final CompanyRepository companyRepository;

    /**
     * Upsert the draft search query for a session: reuse the existing row for this
     * session's unique key, otherwise create a new draft. Mirrors upsertSearchQuery.
     */
    @Transactional
    public SearchQuery upsertForSession(String query, String sessionId, String orgId, String userId, String parsedCriteria) {
        String uniqueKey = "enhanced:" + sessionId;
        Optional<SearchQuery> existing = repository.findByUniqueKeyAndOrgId(uniqueKey, orgId);
        SearchQuery sq = existing.orElseGet(SearchQuery::new);
        sq.setUniqueKey(uniqueKey);
        sq.setQuery(query);
        sq.setParsedCriteria(parsedCriteria);
        sq.setOrgId(orgId);
        sq.setCreatedBy(userId);
        if (sq.getResultCount() == null) sq.setResultCount(0);
        if (sq.getStatus() == null) sq.setStatus("draft");
        return repository.save(sq);
    }

    @Transactional
    public void updateResultCount(Long id, int count, String orgId) {
        repository.updateResultCount(id, count, orgId);
    }

    public record DraftResult(Long searchQueryId, String status, Integer selectedCount) {}

    /** Delete a project's companies then the project itself. Org-scoped. */
    @Transactional
    public void deleteResults(Long id, String orgId) {
        SearchQuery sq = repository.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Search query not found"));
        deleteCompaniesAndQuery(sq, orgId);
    }

    @Transactional
    public DraftResult updateDraft(Long id, Map<String, Object> body, String orgId) {
        SearchQuery sq = repository.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Search query not found"));
        if (body.get("selectedCount") instanceof Number n) sq.setSelectedCount(n.intValue());
        if (body.get("query") instanceof String q) sq.setQuery(q);
        SearchQuery saved = repository.save(sq);
        return new DraftResult(saved.getId(), saved.getStatus(), saved.getSelectedCount());
    }

    /** Delete only the projects owned by the org; returns how many were removed. */
    @Transactional
    public int bulkDelete(java.util.List<Long> ids, String orgId) {
        if (ids == null || ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids array is required");
        }
        int deleted = 0;
        for (Long id : ids) {
            Optional<SearchQuery> sq = repository.findByIdAndOrgId(id, orgId);
            if (sq.isEmpty()) continue;
            deleteCompaniesAndQuery(sq.get(), orgId);
            deleted++;
        }
        return deleted;
    }

    @Transactional
    public SearchQuery rename(Long id, String name, String orgId) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        SearchQuery sq = repository.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Search query not found"));
        sq.setQuery(name.trim());
        return repository.save(sq);
    }

    @Transactional
    public SearchQuery setClockworkProject(Long id, String clockworkProjectId, String orgId) {
        if (clockworkProjectId == null || clockworkProjectId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clockworkProjectId is required");
        }
        SearchQuery sq = repository.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Search query not found"));
        sq.setClockworkProjectId(clockworkProjectId);
        return repository.save(sq);
    }

    private void deleteCompaniesAndQuery(SearchQuery sq, String orgId) {
        for (Company c : companyRepository.findBySearchQueryIdAndOrgId(sq.getId(), orgId)) {
            companyRepository.delete(c);
        }
        repository.delete(sq);
    }
}
