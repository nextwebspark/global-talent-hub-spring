package com.globaltalenthub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globaltalenthub.dto.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppSearchIntentServiceTest {

    @Mock LlmService llmService;
    private final ObjectMapper mapper = new ObjectMapper();

    private AppSearchIntentService service() {
        return new AppSearchIntentService(llmService, mapper);
    }

    private void stubLlm(String json) {
        when(llmService.callWithFallback(anyString(), anyString())).thenReturn(json);
    }

    @Test
    void countryName_mapsToIso2Code() {
        stubLlm("{\"country\":[\"United Arab Emirates\"],\"industry\":[],\"revenueRange\":[],\"employeeRange\":[]}");

        SearchCriteria c = service().parse("FMCG in the UAE", "Search");

        assertThat(c.country()).containsExactly("AE");
    }

    @Test
    void cityAlias_mapsToCode_andCodePassesThrough() {
        stubLlm("{\"country\":[\"Riyadh\",\"AE\"]}");

        SearchCriteria c = service().parse("q", "Search");

        assertThat(c.country()).containsExactlyInAnyOrder("SA", "AE");
    }

    @Test
    void offVocabCountryAndBand_areDropped() {
        stubLlm("{\"country\":[\"US\"],\"revenueRange\":[\"$10-50M\"],\"employeeRange\":[\"501-1k\"]}");

        SearchCriteria c = service().parse("q", "Search");

        assertThat(c.country()).isEmpty();
        assertThat(c.revenueRange()).isEmpty();   // wrong notation dropped
        assertThat(c.employeeRange()).isEmpty();  // 501-1k is not the real 501-1000
    }

    @Test
    void exactBands_validate() {
        stubLlm("{\"revenueRange\":[\"1B-5B\"],\"employeeRange\":[\"501-1000\"]}");

        SearchCriteria c = service().parse("q", "Search");

        assertThat(c.revenueRange()).containsExactly("1B-5B");
        assertThat(c.employeeRange()).containsExactly("501-1000");
    }

    @Test
    void industry_freeTerms_passThroughUnvalidated() {
        stubLlm("{\"industry\":[\"FMCG\",\"Food and Beverage\"]}");

        SearchCriteria c = service().parse("q", "Search");

        assertThat(c.industry()).containsExactly("FMCG", "Food and Beverage");
    }

    @Test
    void peopleKeys_areNeverLlmDerived() {
        // even if the model returns people fields, the parse ignores them
        stubLlm("{\"industry\":[\"FMCG\"],\"positions\":[\"CFO\"]}");

        SearchCriteria c = service().parse("q", "Search");

        assertThat(c.positions()).isNull();
    }

    @Test
    void codeFencedJson_isTolerated() {
        stubLlm("```json\n{\"country\":[\"QA\"]}\n```");

        SearchCriteria c = service().parse("q", "Search");

        assertThat(c.country()).containsExactly("QA");
    }

    @Test
    void llmTimeout_failsOpenToEmpty() {
        when(llmService.callWithFallback(anyString(), anyString()))
            .thenThrow(new RuntimeException("LLM call timed out after 30000ms"));

        SearchCriteria c = service().parse("q", "Search");

        assertThat(c.industry()).isNull();
        assertThat(c.country()).isNull();
    }

    @Test
    void malformedJson_failsOpenToEmpty() {
        stubLlm("not json at all");

        SearchCriteria c = service().parse("q", "Search");

        assertThat(c.country()).isNull();
    }

    @Test
    void importMode_skipsLlm_returnsEmpty() {
        SearchCriteria c = service().parse("", "Import a list");

        assertThat(c.industry()).isNull();
        // llmService never called (no stub needed)
    }

    @Test
    void blankQuery_returnsEmpty() {
        SearchCriteria c = service().parse("  ", "Search");

        assertThat(c.country()).isNull();
    }
}
