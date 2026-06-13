package com.globaltalenthub.service;

import com.globaltalenthub.entity.Company;
import com.globaltalenthub.entity.Executive;
import com.globaltalenthub.entity.SearchQuery;
import com.globaltalenthub.repository.CompanyRepository;
import com.globaltalenthub.repository.ExecutiveRepository;
import com.globaltalenthub.repository.SearchQueryRepository;
import com.globaltalenthub.service.pipeline.LlmClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class EnrichmentServiceTest {

    @Mock SearchQueryRepository searchQueryRepo;
    @Mock CompanyRepository companyRepo;
    @Mock ExecutiveRepository executiveRepo;
    @Mock SectorService sectorService;
    @Mock LlmClassifier classifier;

    @InjectMocks EnrichmentService service;

    private void searchExists() {
        when(searchQueryRepo.findByIdAndOrgId(1L, "org-1")).thenReturn(Optional.of(new SearchQuery()));
    }

    @Test
    void missingRequiredFields_throws400() {
        assertThatThrownBy(() -> service.importCandidate(Map.of("name", "Jane"), "org-1"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("required");
    }

    @Test
    void existingClockworkId_isIdempotent() {
        searchExists();
        Executive existing = new Executive();
        existing.setId(50L);
        existing.setCompanyId(9L);
        when(executiveRepo.findByClockworkId("cw-1")).thenReturn(Optional.of(existing));

        var result = service.importCandidate(Map.of("searchId", 1, "clockworkId", "cw-1", "name", "Jane"), "org-1");

        assertThat(result.alreadyExisted()).isTrue();
        assertThat(result.executiveId()).isEqualTo(50L);
        verify(executiveRepo, never()).save(any());
    }

    @Test
    void attachesToExistingCompany_byName() {
        searchExists();
        when(executiveRepo.findByClockworkId("cw-2")).thenReturn(Optional.empty());
        Company c = new Company();
        c.setId(7L);
        c.setName("Acme Bank");
        when(companyRepo.findBySearchQueryIdAndOrgId(1L, "org-1")).thenReturn(List.of(c));
        when(executiveRepo.save(any())).thenAnswer(i -> {
            Executive e = i.getArgument(0);
            e.setId(60L);
            return e;
        });

        var result = service.importCandidate(Map.of("searchId", 1, "clockworkId", "cw-2",
            "name", "John CFO", "company", "acme bank"), "org-1");

        assertThat(result.companyId()).isEqualTo(7L);
        assertThat(result.alreadyExisted()).isFalse();
        verify(companyRepo, never()).save(any()); // reused, not researched
    }

    @Test
    void researchesAndCreatesCompany_whenNotInSearch() {
        searchExists();
        when(executiveRepo.findByClockworkId("cw-3")).thenReturn(Optional.empty());
        when(companyRepo.findBySearchQueryIdAndOrgId(1L, "org-1")).thenReturn(List.of());
        when(classifier.classify(anyString())).thenReturn(
            "{\"name\":\"Researched Co\",\"sector\":\"Banking\",\"country\":\"UAE\",\"confidence\":8}");
        when(sectorService.normalizeOrInfer(anyString(), any()))
            .thenReturn(new SectorService.SectorResult("Banking", "Financial Services"));
        when(companyRepo.save(any())).thenAnswer(i -> {
            Company c = i.getArgument(0);
            c.setId(80L);
            return c;
        });
        when(executiveRepo.save(any())).thenAnswer(i -> {
            Executive e = i.getArgument(0);
            e.setId(81L);
            return e;
        });

        var result = service.importCandidate(Map.of("searchId", 1, "clockworkId", "cw-3",
            "name", "Sarah CEO", "company", "Researched Co"), "org-1");

        assertThat(result.companyId()).isEqualTo(80L);
        verify(companyRepo).save(any(Company.class));
    }

    @Test
    void noCompanyAndEmptySearch_throws400() {
        searchExists();
        when(executiveRepo.findByClockworkId("cw-4")).thenReturn(Optional.empty());
        when(companyRepo.findBySearchQueryIdAndOrgId(1L, "org-1")).thenReturn(List.of());

        assertThatThrownBy(() -> service.importCandidate(
            Map.of("searchId", 1, "clockworkId", "cw-4", "name", "Nobody"), "org-1"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("No company available");
    }
}
