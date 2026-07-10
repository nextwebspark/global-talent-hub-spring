package com.globaltalenthub.service;

import com.globaltalenthub.dto.AppCompanyDto;
import com.globaltalenthub.dto.FacetsDto;
import com.globaltalenthub.entity.AppCompany;
import com.globaltalenthub.repository.AppCompanyRepository;
import com.globaltalenthub.repository.AppCompanyRepository.FacetRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppCompanyServiceTest {

    @Mock AppCompanyRepository appCompanyRepo;
    @InjectMocks AppCompanyService service;

    private AppCompany sample() {
        AppCompany c = new AppCompany();
        c.setId(1L);
        c.setName("Almarai");
        c.setPrimaryIndustry("FMCG");
        c.setIndustryTags(new String[]{"Food and Beverage", "Dairy"});
        c.setRevenueUsd(4_200_000_000L);
        c.setRevenueRange("1B-5B");
        c.setEmployeeCount(11000);
        c.setEmployeeRange("10000+");
        c.setHqCountry("SA");
        c.setFounded(1977);
        return c;
    }

    @Test
    void getById_returnsMappedDto() {
        when(appCompanyRepo.findById(1L)).thenReturn(Optional.of(sample()));

        AppCompanyDto dto = service.getById(1L);

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.name()).isEqualTo("Almarai");
        assertThat(dto.primaryIndustry()).isEqualTo("FMCG");
        assertThat(dto.industryTags()).containsExactly("Food and Beverage", "Dairy");
        assertThat(dto.revenueUsd()).isEqualTo(4_200_000_000L);
        assertThat(dto.hqCountry()).isEqualTo("SA");
        assertThat(dto.founded()).isEqualTo(1977);
    }

    @Test
    void getById_nullArrays_becomeEmptyLists() {
        AppCompany c = new AppCompany();
        c.setId(2L);
        c.setName("NoArrays Co");
        when(appCompanyRepo.findById(2L)).thenReturn(Optional.of(c));

        AppCompanyDto dto = service.getById(2L);

        assertThat(dto.industryTags()).isEmpty();
        assertThat(dto.markets()).isEmpty();
        assertThat(dto.specialties()).isEmpty();
    }

    @Test
    void getById_unknownId_throws404() {
        when(appCompanyRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404")
            .hasMessageContaining("Company not found");
    }

    // ── search: sort whitelist + size cap + paging ──────────────────────────

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Pageable> stubSearchAndCapturePageable() {
        Page<AppCompany> page = new PageImpl<>(List.of(sample()));
        when(appCompanyRepo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        return ArgumentCaptor.forClass(Pageable.class);
    }

    @Test
    void search_defaultSort_isRevenueDesc() {
        ArgumentCaptor<Pageable> cap = stubSearchAndCapturePageable();

        Page<AppCompanyDto> result = service.search(null, null, null, null, null, null, 0, 25, null, null);

        assertThat(result.getContent()).hasSize(1);
        verify(appCompanyRepo).findAll(any(Specification.class), cap.capture());
        Sort.Order order = cap.getValue().getSort().getOrderFor("revenueUsd");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void search_validSort_isApplied() {
        ArgumentCaptor<Pageable> cap = stubSearchAndCapturePageable();

        service.search(null, null, null, null, null, "name,asc", 0, 25, null, null);

        verify(appCompanyRepo).findAll(any(Specification.class), cap.capture());
        Sort.Order order = cap.getValue().getSort().getOrderFor("name");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void search_unknownSortField_throws400() {
        assertThatThrownBy(() ->
            service.search(null, null, null, null, null, "hqCountry,asc", 0, 25, null, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400")
            .hasMessageContaining("sort");
    }

    @Test
    void search_sizeCappedAt100() {
        ArgumentCaptor<Pageable> cap = stubSearchAndCapturePageable();

        service.search(null, null, null, null, null, null, 0, 5000, null, null);

        verify(appCompanyRepo).findAll(any(Specification.class), cap.capture());
        assertThat(cap.getValue().getPageSize()).isEqualTo(100);
    }

    // ── facets: projection rows → DTO ───────────────────────────────────────

    private FacetRow row(String value, long count) {
        return new FacetRow() {
            public String getValue() { return value; }
            public long getCount() { return count; }
        };
    }

    @Test
    void facets_mapsProjectionRowsToDto() {
        when(appCompanyRepo.facetIndustries())
            .thenReturn(List.of(row("Construction", 2929), row("FMCG", 512)));
        when(appCompanyRepo.facetCountries())
            .thenReturn(List.of(row("AE", 40819), row("SA", 7622)));
        when(appCompanyRepo.facetRevenueRanges())
            .thenReturn(List.of(row("5M-25M", 43533)));
        when(appCompanyRepo.facetEmployeeRanges())
            .thenReturn(List.of(row("1-10", 26345)));

        FacetsDto f = service.facets();

        assertThat(f.industries()).hasSize(2);
        assertThat(f.industries().get(0).value()).isEqualTo("Construction");
        assertThat(f.industries().get(0).count()).isEqualTo(2929);
        assertThat(f.countries().get(0).value()).isEqualTo("AE");
        assertThat(f.revenueRanges().get(0).value()).isEqualTo("5M-25M");
        assertThat(f.employeeRanges().get(0).value()).isEqualTo("1-10");
    }
}
