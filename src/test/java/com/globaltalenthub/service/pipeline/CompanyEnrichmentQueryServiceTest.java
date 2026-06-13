package com.globaltalenthub.service.pipeline;

import com.globaltalenthub.repository.CompanyEnrichmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyEnrichmentQueryServiceTest {

    @Mock
    CompanyEnrichmentRepository repository;

    @InjectMocks
    CompanyEnrichmentQueryService service;

    private static EnrichmentFilter sectorFilter(String... primaries) {
        return new EnrichmentFilter(List.of(primaries), List.of(), List.of(), List.of(),
            List.of(), List.of(), null, "r");
    }

    // Build a 23-column projection row matching the SELECT order.
    private static Object[] row(long id, String name, String country, String primarySector,
                                String[] subTags, BigDecimal confidence) {
        return new Object[]{
            id, "ext-" + id, name, name.toLowerCase().replace(' ', '-'), country, primarySector,
            new String[]{primarySector}, subTags, new String[]{}, null, null,
            null, null, null, null, null, null, confidence,
            null, null, null, null
        };
    }

    @Test
    void noCrucialSignal_returnsEmpty_withoutQuerying() {
        EnrichmentFilter empty = new EnrichmentFilter(List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), null, "r");

        assertThat(service.query(empty, 10)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void poolSize_isMinMaxFormula() {
        when(repository.queryCandidatePool(anyBoolean(), anyString(), anyBoolean(), anyString(), anyInt()))
            .thenReturn(List.of());

        service.query(sectorFilter("Technology & Software"), 10);   // max(50,100)=100
        verify(repository).queryCandidatePool(eq(true), anyString(), eq(false), anyString(), eq(100));

        service.query(sectorFilter("Technology & Software"), 200);  // max(1000,100)=1000 → min(500,..)=500
        verify(repository).queryCandidatePool(eq(true), anyString(), eq(false), anyString(), eq(500));
    }

    @Test
    void rankedByMatchScore_thenConfidence_andSliced() {
        // Filter wants Technology primary. Two tech rows (Direct, score 100) + one
        // non-tech, non-subtag row that scores 0. Tie between the two tech rows is
        // broken by confidence desc.
        var techHigh = row(1, "TechHigh", "United States", "Technology & Software", new String[]{}, new BigDecimal("0.90"));
        var techLow = row(2, "TechLow", "United States", "Technology & Software", new String[]{}, new BigDecimal("0.50"));
        var miss = row(3, "Miss", "United States", "Insurance", new String[]{}, new BigDecimal("0.99"));

        when(repository.queryCandidatePool(anyBoolean(), anyString(), anyBoolean(), anyString(), anyInt()))
            .thenReturn(List.of(miss, techLow, techHigh));

        List<EnrichedCompanyMatch> out = service.query(sectorFilter("Technology & Software"), 2);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).row().companyName()).isEqualTo("TechHigh");
        assertThat(out.get(0).matchScore()).isEqualTo(100);
        assertThat(out.get(1).row().companyName()).isEqualTo("TechLow");
        // The score-0 "Miss" row is sliced off.
    }

    @Test
    void sectorAll_combinesPrimaryAndAdjacent_inArrayLiteral() {
        EnrichmentFilter f = new EnrichmentFilter(
            List.of("Banking & Financial Services"),
            List.of("Insurance"),
            List.of(), List.of(), List.of(), List.of(), null, "r");
        when(repository.queryCandidatePool(anyBoolean(), anyString(), anyBoolean(), anyString(), anyInt()))
            .thenReturn(List.of());

        service.query(f, 10);

        ArgumentCaptor<String> sectorArg = ArgumentCaptor.forClass(String.class);
        verify(repository).queryCandidatePool(eq(true), sectorArg.capture(), eq(false), anyString(), anyInt());
        assertThat(sectorArg.getValue())
            .isEqualTo("{\"Banking & Financial Services\",\"Insurance\"}");
    }

    @Test
    void arrayLiteral_escapesQuotesAndBackslashes() {
        assertThat(CompanyEnrichmentQueryService.toArrayLiteral(List.of("a\"b", "c\\d")))
            .isEqualTo("{\"a\\\"b\",\"c\\\\d\"}");
        assertThat(CompanyEnrichmentQueryService.toArrayLiteral(List.of()))
            .isEqualTo("{}");
    }

    @Test
    void subTagOnly_filter_queriesWithSubTagFlag_noSector() {
        EnrichmentFilter f = new EnrichmentFilter(List.of(), List.of(), List.of("fintech-payments"),
            List.of(), List.of(), List.of(), null, "r");
        when(repository.queryCandidatePool(anyBoolean(), anyString(), anyBoolean(), anyString(), anyInt()))
            .thenReturn(List.of());

        service.query(f, 10);

        verify(repository).queryCandidatePool(eq(false), eq("{}"), eq(true), eq("{\"fintech-payments\"}"), eq(100));
        verify(repository, never()).queryCandidatePool(eq(true), anyString(), anyBoolean(), anyString(), anyInt());
    }
}
