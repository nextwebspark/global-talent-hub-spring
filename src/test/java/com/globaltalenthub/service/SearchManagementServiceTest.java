package com.globaltalenthub.service;

import com.globaltalenthub.entity.Company;
import com.globaltalenthub.entity.SearchQuery;
import com.globaltalenthub.repository.CompanyRepository;
import com.globaltalenthub.repository.ExecutiveRepository;
import com.globaltalenthub.repository.SearchQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static com.globaltalenthub.TestIds.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class SearchManagementServiceTest {

    @Mock SearchQueryRepository searchQueryRepo;
    @Mock CompanyRepository companyRepo;
    @Mock ExecutiveRepository executiveRepo;
    @Mock CoordinateFallbackService coordinateFallbackService;

    @InjectMocks SearchManagementService service;

    private SearchQuery draft() {
        SearchQuery sq = new SearchQuery();
        sq.setId(5L);
        sq.setQuery("tech CFOs");
        sq.setStatus("draft");
        return sq;
    }

    @Test
    void addToProject_promotesDraft_andReassociatesOwnedCompanies() {
        Company c1 = new Company(); c1.setId(1L);
        Company c2 = new Company(); c2.setId(2L);
        when(companyRepo.findByIdInAndSearchSessionIdAndOrgId(List.of(1L, 2L), "sess", uuid("org-1")))
            .thenReturn(List.of(c1, c2));
        when(searchQueryRepo.findByIdAndOrgId(5L, uuid("org-1"))).thenReturn(Optional.of(draft()));
        when(searchQueryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(companyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(executiveRepo.findByCompanyIdAndOrgId(anyLong(), any())).thenReturn(List.of());

        var result = service.addToProject(List.of(1L, 2L), "sess", 5L, uuid("org-1"));

        assertThat(result.companiesAdded()).isEqualTo(2);
        assertThat(result.searchQueryId()).isEqualTo(5L);
        assertThat(c1.getSearchQueryId()).isEqualTo(5L);
        verify(searchQueryRepo).save(any(SearchQuery.class));
    }

    @Test
    void addToProject_noOwnedCompanies_throws400() {
        when(companyRepo.findByIdInAndSearchSessionIdAndOrgId(any(), anyString(), any()))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.addToProject(List.of(9L), "sess", 5L, uuid("org-1")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("No valid companies");
        verify(searchQueryRepo, never()).save(any());
    }

    @Test
    void addToProject_missingArgs_throws400() {
        assertThatThrownBy(() -> service.addToProject(null, "sess", 5L, uuid("org-1")))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("companyIds");
        assertThatThrownBy(() -> service.addToProject(List.of(1L), null, 5L, uuid("org-1")))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("sessionId");
        assertThatThrownBy(() -> service.addToProject(List.of(1L), "sess", null, uuid("org-1")))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("searchQueryId");
    }

    @Test
    void fullResults_appliesCoordinateFallbackPerCompany() {
        Company c = new Company();
        c.setId(1L);
        c.setRegion("Dubai");
        c.setCountry("UAE");
        when(searchQueryRepo.findByIdAndOrgId(5L, uuid("org-1"))).thenReturn(Optional.of(draft()));
        when(companyRepo.findBySearchQueryIdAndOrgId(5L, uuid("org-1"))).thenReturn(List.of(c));
        when(executiveRepo.findByCompanyIdAndOrgId(1L, uuid("org-1"))).thenReturn(List.of());
        when(coordinateFallbackService.apply(any(), any(), any(), any()))
            .thenReturn(new CoordinateFallbackService.Result(new BigDecimal("25.2"), new BigDecimal("55.3"), "city", "Dubai"));

        var results = service.fullResults(5L, uuid("org-1"));

        assertThat(results.companies()).hasSize(1);
        assertThat(results.companies().get(0).company().getLatitude()).isEqualByComparingTo("25.2");
    }

    @Test
    void fullResults_unknownQuery_throws404() {
        when(searchQueryRepo.findByIdAndOrgId(99L, uuid("org-1"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.fullResults(99L, uuid("org-1")))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not found");
    }

    @Test
    void history_capsAt50_andCountsCompanies() {
        SearchQuery q = draft();
        when(searchQueryRepo.findByOrgIdOrderByCreatedAtDesc(uuid("org-1"))).thenReturn(List.of(q));
        when(companyRepo.findBySearchQueryIdAndOrgId(5L, uuid("org-1"))).thenReturn(List.of(new Company(), new Company()));

        var history = service.history(uuid("org-1"));

        assertThat(history).hasSize(1);
        assertThat(history.get(0).companyCount()).isEqualTo(2);
    }
}
