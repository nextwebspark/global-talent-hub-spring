package com.globaltalenthub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globaltalenthub.dto.CreateSearchRunRequest;
import com.globaltalenthub.dto.SearchCriteria;
import com.globaltalenthub.dto.SearchRunDto;
import com.globaltalenthub.entity.AppSearchRun;
import com.globaltalenthub.repository.AppSearchRunRepository;
import com.globaltalenthub.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static com.globaltalenthub.TestIds.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppSearchRunServiceTest {

    @Mock AppSearchRunRepository runRepo;
    @Mock AppSearchIntentService intentService;
    private final ObjectMapper mapper = new ObjectMapper();

    private AppSearchRunService service() {
        return new AppSearchRunService(runRepo, intentService, mapper);
    }

    private static final AuthenticatedUser USER_A =
        new AuthenticatedUser(uuid("u-a"), "a@x.com", uuid("org-a"), "admin");
    private static final AuthenticatedUser USER_B =
        new AuthenticatedUser(uuid("u-b"), "b@x.com", uuid("org-b"), "admin");

    /** save(...) mimics the DB flush: assign an id and apply the @PrePersist defaults. */
    private void stubSave() {
        when(runRepo.save(any(AppSearchRun.class))).thenAnswer(inv -> {
            AppSearchRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(42L);
            if (r.getStatus() == null) r.setStatus("active");
            return r;
        });
    }

    @Test
    void create_setsOrgAndCreatedBy_andPersists() {
        when(intentService.parse(anyString(), anyString())).thenReturn(SearchCriteria.empty());
        stubSave();

        CreateSearchRunRequest req = new CreateSearchRunRequest("FMCG in UAE", "Search", null);
        SearchRunDto dto = service().create(req, USER_A);

        ArgumentCaptor<AppSearchRun> cap = ArgumentCaptor.forClass(AppSearchRun.class);
        org.mockito.Mockito.verify(runRepo).save(cap.capture());
        assertThat(cap.getValue().getOrgId()).isEqualTo(uuid("org-a"));
        assertThat(cap.getValue().getCreatedBy()).isEqualTo(uuid("u-a"));
        assertThat(dto.id()).isEqualTo(42L);
        assertThat(dto.status()).isEqualTo("active");
    }

    @Test
    void create_clientCriteria_winOverLlm_andPeopleKeysStored() {
        // LLM guesses SA; client edits to AE and adds people fields
        when(intentService.parse(anyString(), anyString())).thenReturn(
            new SearchCriteria(List.of("FMCG"), List.of("SA"), null, null, null, null, null));
        stubSave();

        SearchCriteria clientEdit = new SearchCriteria(
            null, List.of("AE"), null, null, List.of("CFO"), List.of("C-Suite"), List.of("15+ years"));
        SearchRunDto dto = service().create(
            new CreateSearchRunRequest("q", "Search", clientEdit), USER_A);

        // client country wins; LLM industry kept (client didn't set it); people stored
        assertThat(dto.parsedCriteria().country()).containsExactly("AE");
        assertThat(dto.parsedCriteria().industry()).containsExactly("FMCG");
        assertThat(dto.parsedCriteria().positions()).containsExactly("CFO");
        assertThat(dto.parsedCriteria().experience()).containsExactly("15+ years");
    }

    @Test
    void create_blankQuery_nonImport_throws400() {
        assertThatThrownBy(() ->
            service().create(new CreateSearchRunRequest("  ", "Search", null), USER_A))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400")
            .hasMessageContaining("query is required");
    }

    @Test
    void create_blankQuery_importMode_isAllowed() {
        when(intentService.parse(any(), anyString())).thenReturn(SearchCriteria.empty());
        stubSave();

        SearchRunDto dto = service().create(
            new CreateSearchRunRequest("", "Import a list", null), USER_A);

        assertThat(dto.id()).isEqualTo(42L);
    }

    @Test
    void get_wrongOrg_throws404() {
        when(runRepo.findByIdAndOrgId(7L, uuid("org-b"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(7L, USER_B))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
    }

    @Test
    void get_roundTripsParsedCriteriaJsonb() {
        AppSearchRun run = new AppSearchRun();
        run.setId(7L);
        run.setOrgId(uuid("org-a"));
        run.setQuery("q");
        run.setStatus("active");
        run.setParsedCriteria(mapper.convertValue(
            new SearchCriteria(List.of("FMCG"), List.of("AE"), null, null, null, null, null),
            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
        when(runRepo.findByIdAndOrgId(7L, uuid("org-a"))).thenReturn(Optional.of(run));

        SearchRunDto dto = service().get(7L, USER_A);

        assertThat(dto.parsedCriteria().industry()).containsExactly("FMCG");
        assertThat(dto.parsedCriteria().country()).containsExactly("AE");
    }

    @Test
    void patch_resultCount_persists_orgScoped() {
        AppSearchRun run = new AppSearchRun();
        run.setId(7L);
        run.setOrgId(uuid("org-a"));
        run.setQuery("q");
        run.setStatus("active");
        when(runRepo.findByIdAndOrgId(7L, uuid("org-a"))).thenReturn(Optional.of(run));
        when(runRepo.save(any(AppSearchRun.class))).thenAnswer(inv -> inv.getArgument(0));

        SearchRunDto dto = service().patch(7L, 24, null, USER_A);

        assertThat(dto.resultCount()).isEqualTo(24);
    }

    @Test
    void patch_criteria_storedVerbatim_noReparse() {
        AppSearchRun run = new AppSearchRun();
        run.setId(7L);
        run.setOrgId(uuid("org-a"));
        run.setQuery("FMCG in SA");
        run.setStatus("active");
        run.setParsedCriteria(java.util.Map.of("industry", java.util.List.of("FMCG")));
        when(runRepo.findByIdAndOrgId(7L, uuid("org-a"))).thenReturn(Optional.of(run));
        when(runRepo.save(any(AppSearchRun.class))).thenAnswer(inv -> inv.getArgument(0));

        // User edited the filters (added an employee band, changed industry) — stored as-is.
        SearchCriteria edited =
            new SearchCriteria(java.util.List.of("Dairy"), null, null,
                java.util.List.of("201-500"), null, null, null);
        SearchRunDto dto = service().patch(7L, null, edited, USER_A);

        assertThat(dto.parsedCriteria().industry()).containsExactly("Dairy");
        assertThat(dto.parsedCriteria().employeeRange()).containsExactly("201-500");
        // No LLM parse happened — intentService was never touched.
        org.mockito.Mockito.verifyNoInteractions(intentService);
    }

    @Test
    void patch_wrongOrg_throws404() {
        when(runRepo.findByIdAndOrgId(7L, uuid("org-b"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().patch(7L, 5, null, USER_B))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
    }
}
