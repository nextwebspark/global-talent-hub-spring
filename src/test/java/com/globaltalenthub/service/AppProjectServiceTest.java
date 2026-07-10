package com.globaltalenthub.service;

import com.globaltalenthub.dto.ClientDto;
import com.globaltalenthub.dto.ClientRef;
import com.globaltalenthub.dto.CreateProjectFromRunRequest;
import com.globaltalenthub.dto.CreateProjectRequest;
import com.globaltalenthub.dto.NewClientInput;
import com.globaltalenthub.dto.PatchProjectCompanyRequest;
import com.globaltalenthub.dto.ProjectCompanyDto;
import com.globaltalenthub.dto.ProjectCompanyInput;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static com.globaltalenthub.TestIds.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppProjectServiceTest {

    @Mock AppProjectRepository projectRepo;
    @Mock AppProjectCompanyRepository projectCompanyRepo;
    @Mock AppClientRepository clientRepo;
    @Mock AppClientService clientService;
    @Mock AppSearchRunRepository searchRunRepo;
    @Mock AppCompanyRepository companyRepo;

    private AppProjectService service() {
        return new AppProjectService(projectRepo, projectCompanyRepo, clientRepo,
            clientService, searchRunRepo, companyRepo, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static final AuthenticatedUser USER =
        new AuthenticatedUser(uuid("u-a"), "a@x.com", uuid("org-a"), "admin");
    private static final AuthenticatedUser OTHER =
        new AuthenticatedUser(uuid("u-b"), "b@x.com", uuid("org-b"), "admin");

    private AppClient client(long id) {
        AppClient c = new AppClient();
        c.setId(id); c.setOrgId(uuid("org-a")); c.setName("Al Rabie");
        return c;
    }

    private void stubRunValid() {
        AppSearchRun run = new AppSearchRun();
        run.setId(42L); run.setOrgId(uuid("org-a")); run.setQuery("q"); run.setStatus("active");
        when(searchRunRepo.findByIdAndOrgId(42L, uuid("org-a"))).thenReturn(Optional.of(run));
    }

    private void stubProjectSave(long id) {
        when(projectRepo.save(any(AppProject.class))).thenAnswer(inv -> {
            AppProject p = inv.getArgument(0);
            if (p.getId() == null) p.setId(id);
            if (p.getStatus() == null) p.setStatus("active");
            return p;
        });
    }

    @Test
    void create_resolvesClient_insertsProjectAndRows_returnsDetail() {
        stubRunValid();
        stubProjectSave(77L);
        AppClient cl = client(9001L);
        when(clientService.resolveOrCreate(any(ClientRef.class), eq(USER))).thenReturn(cl);
        // detail() reloads:
        when(projectRepo.findByIdAndOrgId(77L, uuid("org-a"))).thenAnswer(inv -> {
            AppProject p = new AppProject();
            p.setId(77L); p.setOrgId(uuid("org-a")); p.setName("P"); p.setStatus("active");
            p.setClientId(9001L); p.setSearchRunId(42L);
            return Optional.of(p);
        });
        when(clientRepo.findByIdAndOrgId(9001L, uuid("org-a"))).thenReturn(Optional.of(cl));
        when(clientService.toDto(cl)).thenReturn(new ClientDto(9001L, "Al Rabie", null, null, null));
        AppProjectCompany row = new AppProjectCompany();
        row.setId(1L); row.setProjectId(77L); row.setCompanyId(12L);
        row.setRelevanceType("Direct"); row.setStatus("untriaged"); row.setConfidence(91);
        when(projectCompanyRepo.findByProjectIdAndOrgId(77L, uuid("org-a"))).thenReturn(List.of(row));
        AppCompany co = new AppCompany();
        co.setId(12L); co.setName("Almarai"); co.setHqCountry("SA"); co.setPrimaryIndustry("FMCG");
        when(companyRepo.findAllById(List.of(12L))).thenReturn(List.of(co));

        CreateProjectRequest req = new CreateProjectRequest("P",
            new ClientRef(null, new NewClientInput("Al Rabie", null, 501L)), 42L,
            List.of(new ProjectCompanyInput(12L, "Direct", 91, null, null)));
        ProjectDetailDto dto = service().create(req, USER);

        assertThat(dto.id()).isEqualTo(77L);
        assertThat(dto.client().id()).isEqualTo(9001L);
        assertThat(dto.companies()).hasSize(1);
        assertThat(dto.companies().get(0).name()).isEqualTo("Almarai");
        assertThat(dto.companies().get(0).status()).isEqualTo("untriaged");
    }

    @Test
    void create_blankName_throws400() {
        assertThatThrownBy(() -> service().create(
                new CreateProjectRequest("  ", new ClientRef(1L, null), 42L, List.of()), USER))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400")
            .hasMessageContaining("name is required");
    }

    @Test
    void create_invalidRun_throws400() {
        when(searchRunRepo.findByIdAndOrgId(99L, uuid("org-a"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().create(
                new CreateProjectRequest("P", new ClientRef(1L, null), 99L, List.of()), USER))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400")
            .hasMessageContaining("searchRunId");
    }

    @Test
    void createFromRun_startsEmptyUniverse_noSeeding() {
        AppSearchRun run = new AppSearchRun();
        run.setId(42L); run.setOrgId(uuid("org-a")); run.setQuery("FMCG in SA"); run.setStatus("active");
        run.setParsedCriteria(java.util.Map.of("industry", List.of("FMCG"), "country", List.of("SA")));
        when(searchRunRepo.findByIdAndOrgId(42L, uuid("org-a"))).thenReturn(Optional.of(run));
        stubProjectSave(88L);
        AppClient cl = client(9001L);
        when(clientService.resolveOrCreate(any(ClientRef.class), eq(USER))).thenReturn(cl);

        // detail() reload — no companies seeded, so the project's universe is empty.
        when(projectRepo.findByIdAndOrgId(88L, uuid("org-a"))).thenAnswer(inv -> {
            AppProject p = new AppProject();
            p.setId(88L); p.setOrgId(uuid("org-a")); p.setName("FMCG in SA"); p.setStatus("active");
            p.setClientId(9001L); p.setSearchRunId(42L);
            return Optional.of(p);
        });
        when(clientRepo.findByIdAndOrgId(9001L, uuid("org-a"))).thenReturn(Optional.of(cl));
        when(clientService.toDto(cl)).thenReturn(new ClientDto(9001L, "Al Rabie", null, null, null));
        when(projectCompanyRepo.findByProjectIdAndOrgId(88L, uuid("org-a"))).thenReturn(List.of());

        CreateProjectFromRunRequest req = new CreateProjectFromRunRequest(
            "FMCG in SA", new ClientRef(null, new NewClientInput("Al Rabie", null, null)), 42L);
        ProjectDetailDto dto = service().createFromRun(req, USER);

        assertThat(dto.id()).isEqualTo(88L);
        assertThat(dto.companies()).isEmpty();
        // No catalog query, no join-row inserts, no result-count write under add-on-demand.
        org.mockito.Mockito.verify(projectCompanyRepo, org.mockito.Mockito.never())
            .save(any(AppProjectCompany.class));
        org.mockito.Mockito.verify(companyRepo, org.mockito.Mockito.never())
            .findAll(any(org.springframework.data.jpa.domain.Specification.class), any(PageRequest.class));
    }

    @Test
    void reseedCriteria_updatesRunCriteriaOnly_leavesCompaniesUntouched() {
        AppSearchRun run = new AppSearchRun();
        run.setId(42L); run.setOrgId(uuid("org-a")); run.setQuery("FMCG in SA"); run.setStatus("active");
        run.setParsedCriteria(java.util.Map.of("industry", List.of("FMCG")));
        when(searchRunRepo.findByIdAndOrgId(42L, uuid("org-a"))).thenReturn(Optional.of(run));

        AppProject project = new AppProject();
        project.setId(88L); project.setOrgId(uuid("org-a")); project.setName("FMCG in SA");
        project.setStatus("active"); project.setClientId(9001L); project.setSearchRunId(42L);
        when(projectRepo.findByIdAndOrgId(88L, uuid("org-a"))).thenReturn(Optional.of(project));

        // The consultant's already-added company must remain exactly as-is after a criteria edit.
        AppProjectCompany added = new AppProjectCompany();
        added.setId(1L); added.setProjectId(88L); added.setCompanyId(12L);
        added.setRelevanceType("Direct"); added.setStatus("shortlisted"); added.setConfidence(92);
        when(projectCompanyRepo.findByProjectIdAndOrgId(88L, uuid("org-a"))).thenReturn(List.of(added));

        AppCompany co = new AppCompany();
        co.setId(12L); co.setName("Almarai"); co.setHqCountry("SA"); co.setPrimaryIndustry("Dairy");
        AppClient cl = client(9001L);
        when(clientRepo.findByIdAndOrgId(9001L, uuid("org-a"))).thenReturn(Optional.of(cl));
        when(clientService.toDto(cl)).thenReturn(new ClientDto(9001L, "Al Rabie", null, null, null));
        when(companyRepo.findAllById(List.of(12L))).thenReturn(List.of(co));

        ProjectDetailDto dto = service().reseedCriteria(
            88L, new SearchCriteria(List.of("Dairy"), null, null, null, null, null, null), USER);

        assertThat(dto.id()).isEqualTo(88L);
        // Run stores the edited criteria; project rows are neither deleted nor rebuilt.
        assertThat(run.getParsedCriteria().get("industry")).isEqualTo(List.of("Dairy"));
        org.mockito.Mockito.verify(projectCompanyRepo, org.mockito.Mockito.never())
            .deleteByProjectIdAndOrgId(any(), any());
        org.mockito.Mockito.verify(projectCompanyRepo, org.mockito.Mockito.never())
            .save(any(AppProjectCompany.class));
        // The added company survives, status intact.
        assertThat(dto.companies()).hasSize(1);
        assertThat(dto.companies().get(0).status()).isEqualTo("shortlisted");
    }

    @Test
    void reseedCriteria_unknownProject_throws404() {
        when(projectRepo.findByIdAndOrgId(99L, uuid("org-a"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().reseedCriteria(
                99L, new SearchCriteria(null, null, null, null, null, null, null), USER))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
    }

    @Test
    void createFromRun_unknownRun_throws400() {
        when(searchRunRepo.findByIdAndOrgId(99L, uuid("org-a"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().createFromRun(
                new CreateProjectFromRunRequest("P", new ClientRef(1L, null), 99L), USER))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400")
            .hasMessageContaining("searchRunId");
    }

    @Test
    void list_fillsCountAndClientName() {
        AppProject p = new AppProject();
        p.setId(77L); p.setOrgId(uuid("org-a")); p.setName("P"); p.setStatus("active");
        p.setClientId(9001L); p.setSearchRunId(42L);
        Page<AppProject> page = new PageImpl<>(List.of(p));
        when(projectRepo.findByOrgId(eq(uuid("org-a")), any())).thenReturn(page);
        when(projectCompanyRepo.countByProjectIdAndOrgId(77L, uuid("org-a"))).thenReturn(2L);
        when(clientRepo.findByIdAndOrgId(9001L, uuid("org-a"))).thenReturn(Optional.of(client(9001L)));

        Page<ProjectSummaryDto> out = service().list(USER, PageRequest.of(0, 25));

        assertThat(out.getContent()).hasSize(1);
        assertThat(out.getContent().get(0).companyCount()).isEqualTo(2L);
        assertThat(out.getContent().get(0).clientName()).isEqualTo("Al Rabie");
    }

    @Test
    void detail_wrongOrg_throws404() {
        when(projectRepo.findByIdAndOrgId(77L, uuid("org-b"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().detail(77L, OTHER))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
    }

    // ── Phase 04: listCompanies + patchCompany ──────────────────────────────

    private void stubProjectInOrg() {
        AppProject p = new AppProject();
        p.setId(77L); p.setOrgId(uuid("org-a")); p.setName("P"); p.setStatus("active");
        p.setClientId(9001L); p.setSearchRunId(42L);
        when(projectRepo.findByIdAndOrgId(77L, uuid("org-a"))).thenReturn(Optional.of(p));
    }

    private AppProjectCompany joinRow() {
        AppProjectCompany pc = new AppProjectCompany();
        pc.setId(1L); pc.setOrgId(uuid("org-a")); pc.setProjectId(77L); pc.setCompanyId(12L);
        pc.setRelevanceType("Direct"); pc.setStatus("untriaged"); pc.setConfidence(91);
        return pc;
    }

    @Test
    void listCompanies_pagesAndJoinsDisplayFields() {
        stubProjectInOrg();
        Page<AppProjectCompany> page = new PageImpl<>(List.of(joinRow()));
        when(projectCompanyRepo.findByProjectIdAndOrgId(eq(77L), eq(uuid("org-a")), any()))
            .thenReturn(page);
        AppCompany co = new AppCompany();
        co.setId(12L); co.setName("Almarai"); co.setHqCountry("SA");
        when(companyRepo.findAllById(List.of(12L))).thenReturn(List.of(co));

        Page<ProjectCompanyDto> out = service().listCompanies(77L, USER, PageRequest.of(0, 25));

        assertThat(out.getContent()).hasSize(1);
        assertThat(out.getContent().get(0).name()).isEqualTo("Almarai");
        assertThat(out.getContent().get(0).status()).isEqualTo("untriaged");
    }

    @Test
    void listCompanies_projectNotInOrg_throws404() {
        when(projectRepo.findByIdAndOrgId(77L, uuid("org-b"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listCompanies(77L, OTHER, PageRequest.of(0, 25)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test
    void patchCompany_status_persistsAndReturns() {
        stubProjectInOrg();
        AppProjectCompany pc = joinRow();
        when(projectCompanyRepo.findByProjectIdAndCompanyIdAndOrgId(77L, 12L, uuid("org-a")))
            .thenReturn(Optional.of(pc));
        when(projectCompanyRepo.save(any(AppProjectCompany.class))).thenAnswer(inv -> inv.getArgument(0));
        AppCompany co = new AppCompany(); co.setId(12L); co.setName("Almarai");
        when(companyRepo.findById(12L)).thenReturn(Optional.of(co));

        ProjectCompanyDto dto = service().patchCompany(77L, 12L,
            new PatchProjectCompanyRequest("shortlisted", null, null, null), USER);

        assertThat(dto.status()).isEqualTo("shortlisted");
        assertThat(pc.getStatus()).isEqualTo("shortlisted");
    }

    @Test
    void patchCompany_partial_leavesOtherFields() {
        stubProjectInOrg();
        AppProjectCompany pc = joinRow();
        pc.setMapX(new java.math.BigDecimal("52.0"));
        when(projectCompanyRepo.findByProjectIdAndCompanyIdAndOrgId(77L, 12L, uuid("org-a")))
            .thenReturn(Optional.of(pc));
        when(projectCompanyRepo.save(any(AppProjectCompany.class))).thenAnswer(inv -> inv.getArgument(0));
        when(companyRepo.findById(12L)).thenReturn(Optional.empty());

        ProjectCompanyDto dto = service().patchCompany(77L, 12L,
            new PatchProjectCompanyRequest(null, null, new java.math.BigDecimal("61.5"), null), USER);

        assertThat(dto.mapX()).isEqualByComparingTo("61.5");
        assertThat(pc.getStatus()).isEqualTo("untriaged");        // untouched
        assertThat(pc.getRelevanceType()).isEqualTo("Direct");    // untouched
    }

    @Test
    void patchCompany_invalidStatus_throws400() {
        stubProjectInOrg();
        when(projectCompanyRepo.findByProjectIdAndCompanyIdAndOrgId(77L, 12L, uuid("org-a")))
            .thenReturn(Optional.of(joinRow()));

        assertThatThrownBy(() -> service().patchCompany(77L, 12L,
                new PatchProjectCompanyRequest("bogus", null, null, null), USER))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400").hasMessageContaining("status");
    }

    @Test
    void patchCompany_invalidRelevance_throws400() {
        stubProjectInOrg();
        when(projectCompanyRepo.findByProjectIdAndCompanyIdAndOrgId(77L, 12L, uuid("org-a")))
            .thenReturn(Optional.of(joinRow()));

        assertThatThrownBy(() -> service().patchCompany(77L, 12L,
                new PatchProjectCompanyRequest(null, "Nonsense", null, null), USER))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400").hasMessageContaining("relevanceType");
    }

    @Test
    void patchCompany_companyNotInProject_throws404() {
        stubProjectInOrg();
        when(projectCompanyRepo.findByProjectIdAndCompanyIdAndOrgId(77L, 999L, uuid("org-a")))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().patchCompany(77L, 999L,
                new PatchProjectCompanyRequest("declined", null, null, null), USER))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test
    void patchCompany_projectNotInOrg_throws404() {
        when(projectRepo.findByIdAndOrgId(77L, uuid("org-b"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().patchCompany(77L, 12L,
                new PatchProjectCompanyRequest("declined", null, null, null), OTHER))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }
}
