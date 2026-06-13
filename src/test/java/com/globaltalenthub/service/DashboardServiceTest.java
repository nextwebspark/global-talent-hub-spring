package com.globaltalenthub.service;

import com.globaltalenthub.entity.Company;
import com.globaltalenthub.entity.Executive;
import com.globaltalenthub.entity.Remuneration;
import com.globaltalenthub.entity.SearchQuery;
import com.globaltalenthub.repository.CompanyRepository;
import com.globaltalenthub.repository.ExecutiveRepository;
import com.globaltalenthub.repository.RemunerationRepository;
import com.globaltalenthub.repository.SearchQueryRepository;
import com.globaltalenthub.service.pipeline.LlmClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock SearchQueryRepository searchQueryRepo;
    @Mock CompanyRepository companyRepo;
    @Mock ExecutiveRepository executiveRepo;
    @Mock RemunerationRepository remunerationRepo;
    @Mock LlmClassifier classifier;

    DashboardService service;

    // Real currency converter (deterministic) + mocked repos/LLM.
    @org.junit.jupiter.api.BeforeEach
    void setup() {
        service = new DashboardService(searchQueryRepo, companyRepo, executiveRepo, remunerationRepo,
            new CurrencyConversionService(), classifier);
    }

    private SearchQuery sq() {
        SearchQuery q = new SearchQuery();
        q.setId(1L);
        q.setQuery("UAE banking CFOs");
        return q;
    }

    private Company company(long id, String country, String sector, BigDecimal revenue) {
        Company c = new Company();
        c.setId(id);
        c.setCountry(country);
        c.setSector(sector);
        c.setRevenue(revenue);
        return c;
    }

    private Executive exec(long id, long companyId, String level, String gender, String availability) {
        Executive e = new Executive();
        e.setId(id);
        e.setCompanyId(companyId);
        e.setLevel(level);
        e.setGender(gender);
        e.setAvailability(availability);
        return e;
    }

    @Test
    void unknownSearch_throws404() {
        when(searchQueryRepo.findByIdAndOrgId(99L, "org-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.dashboard(99L, "org-1")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aggregatesCompletion_revenueBands_diversity_concentration() {
        when(searchQueryRepo.findByIdAndOrgId(1L, "org-1")).thenReturn(Optional.of(sq()));
        Company c1 = company(1L, "UAE", "Banking", new BigDecimal("2000000000"));   // $1B–$5B
        Company c2 = company(2L, "UAE", "Banking", new BigDecimal("50000000"));      // <$100M, unmapped
        when(companyRepo.findBySearchQueryIdAndOrgId(1L, "org-1")).thenReturn(List.of(c1, c2));
        when(executiveRepo.findByCompanyIdAndOrgId(1L, "org-1"))
            .thenReturn(List.of(exec(10L, 1L, "C-Suite", "Female", "interested")));
        when(executiveRepo.findByCompanyIdAndOrgId(2L, "org-1")).thenReturn(List.of());
        lenient().when(remunerationRepo.findByExecutiveIdIn(any())).thenReturn(List.of());
        when(classifier.classify(anyString())).thenReturn("UAE Banking Sector Leadership");

        Map<String, Object> d = service.dashboard(1L, "org-1");

        assertThat(d.get("reportTitle")).isEqualTo("UAE Banking Sector Leadership");
        @SuppressWarnings("unchecked")
        Map<String, Object> mapping = (Map<String, Object>) d.get("mappingCompletion");
        assertThat(mapping.get("totalCompanies")).isEqualTo(2);
        assertThat(mapping.get("mappedCount")).isEqualTo(1);
        assertThat(mapping.get("completionPct")).isEqualTo(50);

        @SuppressWarnings("unchecked")
        Map<String, Integer> bands = (Map<String, Integer>) d.get("revenueBands");
        assertThat(bands.get("$1B–$5B")).isEqualTo(1);
        assertThat(bands.get("<$100M")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> concentration = (Map<String, Object>) d.get("concentrationIndex");
        assertThat(concentration.get("label")).isEqualTo("Concentrated"); // 100% in UAE

        @SuppressWarnings("unchecked")
        Map<String, Object> diversity = (Map<String, Object>) d.get("diversity");
        @SuppressWarnings("unchecked")
        Map<String, Integer> gender = (Map<String, Integer>) diversity.get("genderBreakdown");
        assertThat(gender.get("Female")).isEqualTo(1);
    }

    @Test
    void titleFallsBackToRawQuery_whenLlmFails() {
        when(searchQueryRepo.findByIdAndOrgId(1L, "org-1")).thenReturn(Optional.of(sq()));
        when(companyRepo.findBySearchQueryIdAndOrgId(1L, "org-1")).thenReturn(List.of());
        when(classifier.classify(anyString())).thenThrow(new RuntimeException("llm down"));

        Map<String, Object> d = service.dashboard(1L, "org-1");

        assertThat(d.get("reportTitle")).isEqualTo("UAE banking CFOs");
    }

    @Test
    void remunerationMedian_convertedToUSD() {
        when(searchQueryRepo.findByIdAndOrgId(1L, "org-1")).thenReturn(Optional.of(sq()));
        Company c1 = company(1L, "UAE", "Banking", new BigDecimal("2000000000"));
        when(companyRepo.findBySearchQueryIdAndOrgId(1L, "org-1")).thenReturn(List.of(c1));
        Executive e1 = exec(10L, 1L, "C-Suite", "Male", "interested");
        Executive e2 = exec(11L, 1L, "C-Suite", "Male", "interested");
        when(executiveRepo.findByCompanyIdAndOrgId(1L, "org-1")).thenReturn(List.of(e1, e2));
        Remuneration r1 = new Remuneration(); r1.setExecutiveId(10L); r1.setBaseSalary(new BigDecimal("100")); r1.setCurrency("USD");
        Remuneration r2 = new Remuneration(); r2.setExecutiveId(11L); r2.setBaseSalary(new BigDecimal("300")); r2.setCurrency("USD");
        when(remunerationRepo.findByExecutiveIdIn(any())).thenReturn(List.of(r1, r2));
        when(classifier.classify(anyString())).thenReturn("Title");

        Map<String, Object> d = service.dashboard(1L, "org-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> rem = (Map<String, Object>) d.get("remuneration");
        @SuppressWarnings("unchecked")
        Map<String, Object> overall = (Map<String, Object>) rem.get("overall");
        @SuppressWarnings("unchecked")
        Map<String, Long> fixedFees = (Map<String, Long>) overall.get("fixedFees");
        // median of [100, 300] = 200
        assertThat(fixedFees.get("median")).isEqualTo(200L);
        assertThat(fixedFees.get("count")).isEqualTo(2L);
    }
}
