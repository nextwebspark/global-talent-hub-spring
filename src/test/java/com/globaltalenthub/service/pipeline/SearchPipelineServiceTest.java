package com.globaltalenthub.service.pipeline;

import com.globaltalenthub.entity.Company;
import com.globaltalenthub.repository.SearchQueryRepository;
import com.globaltalenthub.service.CompanyService;
import com.globaltalenthub.service.CoordinateFallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.globaltalenthub.TestIds.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchPipelineServiceTest {

    @Mock EnrichmentFilterService filterService;
    @Mock CompanyEnrichmentQueryService queryService;
    @Mock CompanyService companyService;
    @Mock CoordinateFallbackService coordinateFallbackService;
    @Mock SearchQueryRepository searchQueryRepository;

    @InjectMocks SearchPipelineService pipeline;

    private List<String> events;
    private EventSink sink;

    @BeforeEach
    void setup() {
        events = new ArrayList<>();
        sink = (type, message, data) -> events.add(type);
        lenient().when(coordinateFallbackService.apply(any(), any(), any(), any()))
            .thenReturn(new CoordinateFallbackService.Result(null, null, "unknown", null));
    }

    private static EnrichmentFilter filter(List<String> primaries, List<String> adjacents) {
        return new EnrichmentFilter(primaries, adjacents, List.of(), List.of(), List.of(), List.of(), null, "rationale");
    }

    private static EnrichedCompanyMatch match(String name, String sector) {
        EnrichedRow row = new EnrichedRow(1L, "ext", name, "slug", "United States", sector,
            List.of(), List.of(), List.of(), null, "desc", null, null, null, null, null, null,
            new BigDecimal("0.9"), null, null, null, null);
        return new EnrichedCompanyMatch(row, CompanyScore.score(row.toScorable(),
            filter(List.of(sector), List.of())));
    }

    @Test
    void happyPath_emitsFullSequence_andPersists() {
        when(filterService.extract(any(), any())).thenReturn(filter(List.of("Technology & Software"), List.of("Telecommunications")));
        when(queryService.query(any(), anyInt())).thenReturn(List.of(match("Acme", "Technology & Software")));
        Company saved = new Company();
        saved.setId(7L);
        saved.setName("Acme");
        when(companyService.upsertNonDestructive(any(), any(), any(), any()))
            .thenReturn(new CompanyService.UpsertResult(saved, true));

        pipeline.runSeedListEnhancedStream("top tech", 5L, uuid("org-1"), 10, () -> false, "sess", null, sink);

        assertThat(events).containsExactly(
            "status", "intent_extracted", "adjacent_sector_found", "status",
            "company_found", "company_enriched", "search_complete");
        verify(searchQueryRepository).updateResultCount(5L, 1, uuid("org-1"));
    }

    @Test
    void unmappedFilter_emitsNoResults_andStops() {
        // empty() yields the fallback rationale → isUnmapped true.
        when(filterService.extract(any(), any())).thenReturn(EnrichmentFilter.empty("gibberish"));

        pipeline.runSeedListEnhancedStream("gibberish", 5L, uuid("org-1"), 10, () -> false, "sess", null, sink);

        assertThat(events).containsExactly("status", "intent_extracted", "no_results");
        verify(queryService, never()).query(any(), anyInt());
    }

    @Test
    void noRows_emitsSearchCompleteZero() {
        when(filterService.extract(any(), any())).thenReturn(filter(List.of("Insurance"), List.of()));
        when(queryService.query(any(), anyInt())).thenReturn(List.of());

        pipeline.runSeedListEnhancedStream("insurers", 5L, uuid("org-1"), 10, () -> false, "sess", null, sink);

        assertThat(events).containsExactly("status", "intent_extracted", "status", "search_complete");
        verify(companyService, never()).upsertNonDestructive(any(), any(), any(), any());
    }

    @Test
    void queryError_emitsError() {
        when(filterService.extract(any(), any())).thenReturn(filter(List.of("Insurance"), List.of()));
        when(queryService.query(any(), anyInt())).thenThrow(new RuntimeException("db down"));

        pipeline.runSeedListEnhancedStream("insurers", 5L, uuid("org-1"), 10, () -> false, "sess", null, sink);

        assertThat(events).containsExactly("status", "intent_extracted", "status", "error");
    }

    @Test
    void abortedBeforeQuery_stopsEarly() {
        when(filterService.extract(any(), any())).thenReturn(filter(List.of("Insurance"), List.of()));

        pipeline.runSeedListEnhancedStream("insurers", 5L, uuid("org-1"), 10, () -> true, "sess", null, sink);

        // status, intent_extracted emitted; abort checked before the "Querying..." status.
        assertThat(events).containsExactly("status", "intent_extracted");
        verify(queryService, never()).query(any(), anyInt());
    }

    @Test
    void relevanceRationale_directWithSoftSignals() {
        EnrichedRow row = new EnrichedRow(1L, "ext", "Acme", "slug", "United States", "Technology & Software",
            List.of(), List.of(), List.of(), null, "desc", "1k-5k", null, "$1-10B", null, null, null,
            new BigDecimal("0.9"), null, null, null, null);
        EnrichmentFilter f = new EnrichmentFilter(List.of("Technology & Software"), List.of(), List.of(),
            List.of("United States"), List.of("1k-5k"), List.of("$1-10B"), null, "Tech firms in the US");
        EnrichedCompanyMatch m = new EnrichedCompanyMatch(row, CompanyScore.score(row.toScorable(), f));

        String rationale = SearchPipelineService.relevanceRationale(m, f);

        assertThat(rationale).startsWith("Primary sector match (Technology & Software)");
        assertThat(rationale).contains("in target geography");
        assertThat(rationale).endsWith("Tech firms in the US");
    }
}
