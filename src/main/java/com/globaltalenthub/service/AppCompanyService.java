package com.globaltalenthub.service;

import com.globaltalenthub.dto.AppCompanyDto;
import com.globaltalenthub.dto.FacetCount;
import com.globaltalenthub.dto.FacetsDto;
import com.globaltalenthub.entity.AppProjectCompany;
import com.globaltalenthub.repository.AppCompanyRepository;
import com.globaltalenthub.repository.AppCompanyRepository.FacetRow;
import com.globaltalenthub.repository.AppCompanySpecs;
import com.globaltalenthub.repository.AppProjectCompanyRepository;
import com.globaltalenthub.repository.AppProjectRepository;
import com.globaltalenthub.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read path over the {@code app_companies} master catalog. Reads are shared
 * (no org filter); auth is still required at the controller.
 */
@Service
@RequiredArgsConstructor
public class AppCompanyService {

    private final AppCompanyRepository appCompanyRepo;
    private final AppProjectCompanyRepository projectCompanyRepo;
    private final AppProjectRepository projectRepo;

    private static final int MAX_PAGE_SIZE = 100;

    /** API sort field → entity property. Anything else is rejected. */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
        "name", "name",
        "revenueUsd", "revenueUsd",
        "employeeCount", "employeeCount",
        "founded", "founded");

    public Page<AppCompanyDto> search(String q,
                                      List<String> industries,
                                      List<String> countries,
                                      List<String> revenueRanges,
                                      List<String> employeeRanges,
                                      String sort,
                                      int page,
                                      int size,
                                      Long projectId,
                                      AuthenticatedUser user) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), cappedSize(size), parseSort(sort));
        Page<com.globaltalenthub.entity.AppCompany> results = appCompanyRepo
            .findAll(AppCompanySpecs.build(q, industries, countries, revenueRanges, employeeRanges), pageable);

        // When the browse is project-scoped, stamp each catalog row with its membership status so
        // already-added / declined cards render correctly and don't re-appear as fresh in the UI.
        Map<Long, String> statusByCompany = projectMembership(projectId, user);
        return results.map(c -> AppCompanyDto.from(c, statusByCompany.get(c.getId())));
    }

    /**
     * companyId → project status for the given project, empty when no project is scoped or the
     * project isn't in the caller's org (org-guard — a foreign projectId simply yields no stamps).
     */
    private Map<Long, String> projectMembership(Long projectId, AuthenticatedUser user) {
        if (projectId == null || user == null || user.orgId() == null) {
            return Map.of();
        }
        if (projectRepo.findByIdAndOrgId(projectId, user.orgId()).isEmpty()) {
            return Map.of();
        }
        return projectCompanyRepo.findByProjectIdAndOrgId(projectId, user.orgId()).stream()
            .collect(Collectors.toMap(AppProjectCompany::getCompanyId, AppProjectCompany::getStatus,
                (a, b) -> a));
    }

    public FacetsDto facets() {
        return new FacetsDto(
            toCounts(appCompanyRepo.facetIndustries()),
            toCounts(appCompanyRepo.facetCountries()),
            toCounts(appCompanyRepo.facetRevenueRanges()),
            toCounts(appCompanyRepo.facetEmployeeRanges()));
    }

    private int cappedSize(int size) {
        if (size < 1) return 1;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /** Parse {@code field,dir}; default {@code revenueUsd,desc}; reject unknown field (400). */
    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "revenueUsd");
        }
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        String prop = SORT_WHITELIST.get(field);
        if (prop == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unsupported sort field: " + field);
        }
        Sort.Direction dir = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
            ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, prop);
    }

    private List<FacetCount> toCounts(List<FacetRow> rows) {
        return rows.stream().map(r -> new FacetCount(r.getValue(), r.getCount())).toList();
    }

    public AppCompanyDto getById(Long id) {
        return appCompanyRepo.findById(id)
            .map(AppCompanyDto::from)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Company not found"));
    }
}
