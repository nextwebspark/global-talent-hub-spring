package com.globaltalenthub.controller;

import com.globaltalenthub.dto.AddProjectCompanyRequest;
import com.globaltalenthub.dto.CreateProjectFromRunRequest;
import com.globaltalenthub.dto.CreateProjectRequest;
import com.globaltalenthub.dto.PatchProjectCompanyRequest;
import com.globaltalenthub.dto.ProjectCompanyDto;
import com.globaltalenthub.dto.ProjectDetailDto;
import com.globaltalenthub.dto.ProjectSummaryDto;
import com.globaltalenthub.dto.ReseedCriteriaRequest;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.AppProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * ALAC Talent Map — projects ("search maps"). Org-scoped; {@code /api/app/**} already
 * requires a valid JWT.
 */
@RestController
@RequiredArgsConstructor
public class AppProjectController {

    private final AppProjectService projectService;

    private static final int MAX_PAGE_SIZE = 100;

    @PostMapping("/api/app/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectDetailDto create(@RequestBody CreateProjectRequest req,
                                   @AuthenticationPrincipal AuthenticatedUser user) {
        return projectService.create(req, user);
    }

    /**
     * Talent Map flow: create a project directly from a search run, auto-seeding its whole
     * matched universe as {@code untriaged} companies. No client-side company selection.
     */
    @PostMapping("/api/app/projects/from-run")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectDetailDto createFromRun(@RequestBody CreateProjectFromRunRequest req,
                                          @AuthenticationPrincipal AuthenticatedUser user) {
        return projectService.createFromRun(req, user);
    }

    @GetMapping("/api/app/projects")
    public Page<ProjectSummaryDto> list(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "25") int size,
                                        @AuthenticationPrincipal AuthenticatedUser user) {
        int capped = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), capped,
            Sort.by(Sort.Direction.DESC, "updatedAt"));
        return projectService.list(user, pageable);
    }

    @GetMapping("/api/app/projects/{id}")
    public ProjectDetailDto get(@PathVariable Long id,
                                @AuthenticationPrincipal AuthenticatedUser user) {
        return projectService.detail(id, user);
    }

    @GetMapping("/api/app/projects/{id}/companies")
    public Page<ProjectCompanyDto> listCompanies(@PathVariable Long id,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "25") int size,
                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        int capped = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), capped, Sort.by(Sort.Direction.ASC, "id"));
        return projectService.listCompanies(id, user, pageable);
    }

    /** Add one catalog company to the project's universe (sourcing add-on-demand). Idempotent. */
    @PostMapping("/api/app/projects/{id}/companies")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectCompanyDto addCompany(@PathVariable Long id,
                                        @RequestBody AddProjectCompanyRequest req,
                                        @AuthenticationPrincipal AuthenticatedUser user) {
        return projectService.addCompany(id, req, user);
    }

    @PatchMapping("/api/app/projects/{id}/companies/{companyId}")
    public ProjectCompanyDto patchCompany(@PathVariable Long id,
                                          @PathVariable Long companyId,
                                          @RequestBody PatchProjectCompanyRequest req,
                                          @AuthenticationPrincipal AuthenticatedUser user) {
        return projectService.patchCompany(id, companyId, req, user);
    }

    /** Remove a company from the project's universe (un-add). */
    @DeleteMapping("/api/app/projects/{id}/companies/{companyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCompany(@PathVariable Long id,
                              @PathVariable Long companyId,
                              @AuthenticationPrincipal AuthenticatedUser user) {
        projectService.removeCompany(id, companyId, user);
    }

    /** Re-filter the project's universe with edited criteria (preserves triage on survivors). */
    @PutMapping("/api/app/projects/{id}/criteria")
    public ProjectDetailDto reseedCriteria(@PathVariable Long id,
                                           @RequestBody ReseedCriteriaRequest req,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return projectService.reseedCriteria(id, req.criteria(), user);
    }
}
