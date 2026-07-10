package com.globaltalenthub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globaltalenthub.dto.AddProjectCompanyRequest;
import com.globaltalenthub.dto.ClientDto;
import com.globaltalenthub.dto.CreateProjectFromRunRequest;
import com.globaltalenthub.dto.CreateProjectRequest;
import com.globaltalenthub.dto.ProjectCompanyDto;
import com.globaltalenthub.dto.ProjectCompanyInput;
import com.globaltalenthub.dto.PatchProjectCompanyRequest;
import com.globaltalenthub.dto.ProjectDetailDto;
import com.globaltalenthub.dto.ProjectSummaryDto;
import com.globaltalenthub.dto.SearchCriteria;
import com.globaltalenthub.entity.AppClient;
import com.globaltalenthub.entity.AppCompany;
import com.globaltalenthub.entity.AppProject;
import com.globaltalenthub.entity.AppProjectCompany;
import com.globaltalenthub.entity.AppSearchRun;
import com.globaltalenthub.repository.AppClientRepository;
import com.globaltalenthub.repository.AppCompanyRepository;
import com.globaltalenthub.repository.AppProjectCompanyRepository;
import com.globaltalenthub.repository.AppProjectRepository;
import com.globaltalenthub.repository.AppSearchRunRepository;
import com.globaltalenthub.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Create + read projects. Create resolves the client via {@link AppClientService}
 * (existing or new), validates the run, and inserts the project plus one join row per
 * selected company — all transactional.
 */
@Service
@RequiredArgsConstructor
public class AppProjectService {

    private final AppProjectRepository projectRepo;
    private final AppProjectCompanyRepository projectCompanyRepo;
    private final AppClientRepository clientRepo;
    private final AppClientService clientService;
    private final AppSearchRunRepository searchRunRepo;
    private final AppCompanyRepository companyRepo;
    private final ObjectMapper objectMapper;

    @Transactional
    public ProjectDetailDto create(CreateProjectRequest req, AuthenticatedUser user) {
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project name is required");
        }
        if (req.searchRunId() == null
            || searchRunRepo.findByIdAndOrgId(req.searchRunId(), user.orgId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown searchRunId");
        }

        AppClient client = clientService.resolveOrCreate(req.client(), user);

        AppProject project = new AppProject();
        project.setOrgId(user.orgId());
        project.setCreatedBy(user.userId());
        project.setName(req.name().trim());
        project.setClientId(client.getId());
        project.setSearchRunId(req.searchRunId());
        project = projectRepo.save(project);

        List<ProjectCompanyInput> inputs = req.companies() == null ? List.of() : req.companies();
        for (ProjectCompanyInput in : inputs) {
            AppProjectCompany pc = new AppProjectCompany();
            pc.setOrgId(user.orgId());
            pc.setProjectId(project.getId());
            pc.setCompanyId(in.companyId());
            pc.setRelevanceType(in.relevanceType() != null ? in.relevanceType() : "Direct");
            pc.setConfidence(in.confidence());
            pc.setMapX(in.mapX());
            pc.setMapY(in.mapY());
            projectCompanyRepo.save(pc);
        }

        return detail(project.getId(), user);
    }

    /**
     * Talent Map flow: turn a search run into a project. The project starts with an <b>empty</b>
     * universe — companies are no longer pre-seeded. The sourcing screen browses the master
     * catalog (project-aware {@code /companies/search}) and the consultant adds companies on
     * demand, so a {@code ProjectCompany} row exists only for a company the user explicitly
     * picked (in universe / shortlisted / declined). The run's {@code parsed_criteria} is kept
     * as the default filter for that browse.
     */
    @Transactional
    public ProjectDetailDto createFromRun(CreateProjectFromRunRequest req, AuthenticatedUser user) {
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project name is required");
        }
        if (req.searchRunId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "searchRunId is required");
        }
        AppSearchRun run = searchRunRepo.findByIdAndOrgId(req.searchRunId(), user.orgId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown searchRunId"));

        AppClient client = clientService.resolveOrCreate(req.client(), user);

        AppProject project = new AppProject();
        project.setOrgId(user.orgId());
        project.setCreatedBy(user.userId());
        project.setName(req.name().trim());
        project.setClientId(client.getId());
        project.setSearchRunId(run.getId());
        project = projectRepo.save(project);

        return detail(project.getId(), user);
    }

    /**
     * Update the project's stored search criteria (the default filter its sourcing browse uses).
     * Under the add-on-demand model this only re-writes the run's {@code parsed_criteria} — it does
     * <b>not</b> touch the project's companies. Companies the consultant already added stay exactly
     * as they are; the edited criteria simply re-filter the catalog stream on the next browse.
     */
    @Transactional
    public ProjectDetailDto reseedCriteria(Long projectId, SearchCriteria criteria, AuthenticatedUser user) {
        AppProject project = projectRepo.findByIdAndOrgId(projectId, user.orgId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project"));

        SearchCriteria edited = criteria == null ? SearchCriteria.empty() : criteria;

        if (project.getSearchRunId() != null) {
            searchRunRepo.findByIdAndOrgId(project.getSearchRunId(), user.orgId()).ifPresent(run -> {
                run.setParsedCriteria(
                    objectMapper.convertValue(edited, new TypeReference<Map<String, Object>>() {}));
                searchRunRepo.save(run);
            });
        }
        return detail(projectId, user);
    }

    @Transactional(readOnly = true)
    public Page<ProjectSummaryDto> list(AuthenticatedUser user, Pageable pageable) {
        return projectRepo.findByOrgId(user.orgId(), pageable).map(p -> {
            long count = projectCompanyRepo.countByProjectIdAndOrgId(p.getId(), user.orgId());
            String clientName = clientRepo.findByIdAndOrgId(p.getClientId(), user.orgId())
                .map(AppClient::getName).orElse(null);
            return new ProjectSummaryDto(p.getId(), p.getName(), p.getClientId(), clientName,
                count, p.getStatus(), p.getCreatedAt());
        });
    }

    @Transactional(readOnly = true)
    public ProjectDetailDto detail(Long id, AuthenticatedUser user) {
        AppProject project = projectRepo.findByIdAndOrgId(id, user.orgId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        ClientDto client = clientRepo.findByIdAndOrgId(project.getClientId(), user.orgId())
            .map(clientService::toDto).orElse(null);

        List<AppProjectCompany> rows = projectCompanyRepo.findByProjectIdAndOrgId(id, user.orgId());
        Map<Long, AppCompany> byId = companyRepo.findAllById(
                rows.stream().map(AppProjectCompany::getCompanyId).toList())
            .stream().collect(Collectors.toMap(AppCompany::getId, Function.identity()));

        List<ProjectCompanyDto> companies = rows.stream()
            .map(pc -> toCompanyDto(pc, byId.get(pc.getCompanyId()))).toList();

        return new ProjectDetailDto(project.getId(), project.getName(), project.getStatus(),
            project.getSearchRunId(), client, companies);
    }

    // ── Phase 04: project universe list + per-company edit ──────────────────

    private static final Set<String> STATUSES =
        Set.of("untriaged", "in_universe", "shortlisted", "declined");
    private static final Set<String> RELEVANCE_TYPES =
        Set.of("Direct", "Adjacent", "AI Inferred");

    @Transactional(readOnly = true)
    public Page<ProjectCompanyDto> listCompanies(Long projectId, AuthenticatedUser user, Pageable pageable) {
        assertProjectInOrg(projectId, user);
        Page<AppProjectCompany> page =
            projectCompanyRepo.findByProjectIdAndOrgId(projectId, user.orgId(), pageable);
        Map<Long, AppCompany> byId = companyRepo.findAllById(
                page.getContent().stream().map(AppProjectCompany::getCompanyId).toList())
            .stream().collect(Collectors.toMap(AppCompany::getId, Function.identity()));
        return page.map(pc -> toCompanyDto(pc, byId.get(pc.getCompanyId())));
    }

    @Transactional
    public ProjectCompanyDto patchCompany(Long projectId, Long companyId,
                                          PatchProjectCompanyRequest req, AuthenticatedUser user) {
        assertProjectInOrg(projectId, user);
        AppProjectCompany pc = projectCompanyRepo
            .findByProjectIdAndCompanyIdAndOrgId(projectId, companyId, user.orgId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Company not in this project"));

        if (req.status() != null) {
            if (!STATUSES.contains(req.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: " + req.status());
            }
            pc.setStatus(req.status());
        }
        if (req.relevanceType() != null) {
            if (!RELEVANCE_TYPES.contains(req.relevanceType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown relevanceType: " + req.relevanceType());
            }
            pc.setRelevanceType(req.relevanceType());
        }
        if (req.mapX() != null) pc.setMapX(req.mapX());
        if (req.mapY() != null) pc.setMapY(req.mapY());

        pc = projectCompanyRepo.save(pc);
        AppCompany c = companyRepo.findById(pc.getCompanyId()).orElse(null);
        return toCompanyDto(pc, c);
    }

    /** Default status for a newly added company (the "Add to universe" action). */
    private static final String DEFAULT_ADD_STATUS = "in_universe";

    /**
     * Add one catalog company to the project's universe on demand. Idempotent on the unique
     * {@code (project_id, company_id)} constraint: if the company is already in the project the
     * existing row is returned (optionally re-statused) rather than raising a duplicate-key error.
     */
    @Transactional
    public ProjectCompanyDto addCompany(Long projectId, AddProjectCompanyRequest req,
                                        AuthenticatedUser user) {
        assertProjectInOrg(projectId, user);
        if (req.companyId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "companyId is required");
        }
        AppCompany company = companyRepo.findById(req.companyId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown companyId"));

        String status = req.status() != null ? req.status() : DEFAULT_ADD_STATUS;
        if (!STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: " + status);
        }
        if (req.relevanceType() != null && !RELEVANCE_TYPES.contains(req.relevanceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown relevanceType: " + req.relevanceType());
        }

        AppProjectCompany pc = projectCompanyRepo
            .findByProjectIdAndCompanyIdAndOrgId(projectId, req.companyId(), user.orgId())
            .orElseGet(() -> {
                AppProjectCompany fresh = new AppProjectCompany();
                fresh.setOrgId(user.orgId());
                fresh.setProjectId(projectId);
                fresh.setCompanyId(req.companyId());
                return fresh;
            });
        pc.setStatus(status);
        if (req.relevanceType() != null) pc.setRelevanceType(req.relevanceType());
        if (req.confidence() != null) pc.setConfidence(req.confidence());

        pc = projectCompanyRepo.save(pc);
        return toCompanyDto(pc, company);
    }

    /** Remove a company from the project's universe (un-add). No-op-safe if already absent. */
    @Transactional
    public void removeCompany(Long projectId, Long companyId, AuthenticatedUser user) {
        assertProjectInOrg(projectId, user);
        projectCompanyRepo.findByProjectIdAndCompanyIdAndOrgId(projectId, companyId, user.orgId())
            .ifPresent(projectCompanyRepo::delete);
    }

    /** 404 if the project isn't in the caller's org — never trust projectId from the path alone. */
    private void assertProjectInOrg(Long projectId, AuthenticatedUser user) {
        if (projectRepo.findByIdAndOrgId(projectId, user.orgId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
    }

    private ProjectCompanyDto toCompanyDto(AppProjectCompany pc, AppCompany c) {
        return new ProjectCompanyDto(
            pc.getId(), pc.getCompanyId(),
            c != null ? c.getName() : null,
            c != null ? c.getLogo() : null,
            c != null ? c.getPrimaryIndustry() : null,
            c != null ? c.getHqCountry() : null,
            c != null ? c.getHqCity() : null,
            c != null ? c.getRevenueUsd() : null,
            c != null ? c.getRevenueRange() : null,
            c != null ? c.getEmployeeCount() : null,
            c != null ? c.getEmployeeRange() : null,
            c != null ? c.getOrgType() : null,
            c != null ? c.getOwnership() : null,
            c != null ? c.getFounded() : null,
            c != null ? c.getWebsite() : null,
            c != null ? c.getLinkedinUrl() : null,
            c != null ? c.getDescription() : null,
            c != null ? c.getIndustryTags() : null,
            c != null ? c.getSpecialties() : null,
            pc.getRelevanceType(), pc.getConfidence(), pc.getStatus(),
            pc.getMapX(), pc.getMapY());
    }
}
