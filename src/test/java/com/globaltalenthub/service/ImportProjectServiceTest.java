package com.globaltalenthub.service;

import com.globaltalenthub.entity.Company;
import com.globaltalenthub.entity.Executive;
import com.globaltalenthub.entity.SearchQuery;
import com.globaltalenthub.repository.CompanyRepository;
import com.globaltalenthub.repository.ExecutiveRepository;
import com.globaltalenthub.repository.SearchQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ImportProjectServiceTest {

    @Mock SearchQueryRepository searchQueryRepo;
    @Mock CompanyRepository companyRepo;
    @Mock ExecutiveRepository executiveRepo;

    @InjectMocks ImportProjectService service;

    @Test
    void noRecords_throws400() {
        assertThatThrownBy(() -> service.importProject(Map.of("mappings", Map.of()), "org-1", "u1"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("No records");
    }

    @Test
    void noMappings_throws400() {
        assertThatThrownBy(() -> service.importProject(
            Map.of("records", List.of(Map.of("A", "x"))), "org-1", "u1"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("mappings");
    }

    @Test
    void importsRecords_dedupesCompaniesByName() {
        when(searchQueryRepo.save(any())).thenAnswer(i -> {
            SearchQuery q = i.getArgument(0);
            if (q.getId() == null) q.setId(1L);
            return q;
        });
        AtomicLong companyId = new AtomicLong(100);
        when(companyRepo.save(any())).thenAnswer(i -> {
            Company c = i.getArgument(0);
            if (c.getId() == null) c.setId(companyId.incrementAndGet());
            return c;
        });
        when(executiveRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Map<String, String> mappings = Map.of("name", "Name", "company", "Company", "country", "Country");
        List<Map<String, Object>> records = List.of(
            Map.of("Name", "Jane", "Company", "Acme", "Country", "UAE"),
            Map.of("Name", "John", "Company", "Acme", "Country", "UAE"),  // same company
            Map.of("Name", "Sara", "Company", "Beta", "Country", "Qatar"));

        var result = service.importProject(Map.of("projectName", "Q1 Import", "records", records, "mappings", mappings),
            "org-1", "u1");

        assertThat(result.imported()).isEqualTo(3);       // 3 executives
        assertThat(result.searchQueryId()).isEqualTo(1L);
        verify(companyRepo, times(2)).save(any(Company.class));   // 2 distinct companies
        verify(executiveRepo, times(3)).save(any(Executive.class));
    }

    @Test
    void skipsBlankRecords() {
        when(searchQueryRepo.save(any())).thenAnswer(i -> {
            SearchQuery q = i.getArgument(0);
            if (q.getId() == null) q.setId(1L);
            return q;
        });

        Map<String, String> mappings = Map.of("name", "Name", "company", "Company");
        List<Map<String, Object>> records = List.of(Map.of("Other", "irrelevant"));

        var result = service.importProject(Map.of("records", records, "mappings", mappings), "org-1", "u1");

        assertThat(result.imported()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(1);
    }
}
