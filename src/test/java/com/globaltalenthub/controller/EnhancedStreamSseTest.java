package com.globaltalenthub.controller;

import com.globaltalenthub.entity.SearchQuery;
import com.globaltalenthub.repository.SearchSessionRepository;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.BriefSummaryService;
import com.globaltalenthub.service.SearchQueryService;
import com.globaltalenthub.service.pipeline.EventSink;
import com.globaltalenthub.service.pipeline.SearchPipelineService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static com.globaltalenthub.TestIds.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SearchController.class,
    excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
    excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
        type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
        classes = com.globaltalenthub.security.SupabaseJwtFilter.class))
@Import(EnhancedStreamSseTest.SyncExecutorConfig.class)
class EnhancedStreamSseTest {

    @TestConfiguration
    static class SyncExecutorConfig {
        @Bean("sseTaskExecutor")
        Executor sseTaskExecutor() {
            return Runnable::run; // run inline so MockMvc async completes deterministically
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockBean SearchPipelineService pipelineService;
    @MockBean SearchQueryService searchQueryService;
    @MockBean SearchSessionRepository searchSessionRepository;
    @MockBean BriefSummaryService briefSummaryService;
    @MockBean com.globaltalenthub.service.BriefExtractService briefExtractService;
    @MockBean com.globaltalenthub.service.SearchManagementService searchManagementService;

    private static final AuthenticatedUser USER = new AuthenticatedUser(uuid("u1"), "u1@example.com", uuid("org-1"), "admin");

    private void stubQueryAndSession() {
        SearchQuery sq = new SearchQuery();
        sq.setId(99L);
        when(searchQueryService.upsertForSession(any(), any(), any(), any(), any())).thenReturn(sq);
        when(searchSessionRepository.findById(any())).thenReturn(Optional.empty());
        when(searchSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private MvcResult perform(String query, String sessionId) throws Exception {
        return mockMvc.perform(get("/api/search/enhanced-stream")
                .param("query", query)
                .param("sessionId", sessionId)
                .with(authentication(new UsernamePasswordAuthenticationToken(USER, null, List.of()))))
            .andExpect(request().asyncStarted())
            .andReturn();
    }

    @Test
    void emitsFullEventSequence_inOrder() throws Exception {
        stubQueryAndSession();
        Answer<Void> drive = inv -> {
            EventSink sink = inv.getArgument(7);
            sink.emit("intent_extracted", "Sectors: Technology & Software", Map.of("intent", "x"));
            sink.emit("adjacent_sector_found", "AI suggests 1", Map.of("adjacentSectors", List.of("Insurance")));
            sink.emit("company_found", "Found: Acme", Map.of("name", "Acme"));
            sink.emit("company_enriched", "Classified: Acme", Map.of("company", Map.of("name", "Acme")));
            sink.emit("search_complete", "done", Map.of("totalCompanies", 1));
            return null;
        };
        Mockito.doAnswer(drive).when(pipelineService).runSeedListEnhancedStream(
            any(), any(), any(), anyInt(), any(), any(), any(), any());

        MvcResult mvc = perform("top 5 tech companies", "sess-1");

        String body = mockMvc.perform(asyncDispatch(mvc))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andReturn().getResponse().getContentAsString();

        assertOrder(body, "search_created", "intent_extracted", "adjacent_sector_found",
            "company_found", "company_enriched", "search_complete", "done");
    }

    @Test
    void unmappedFilter_emitsNoResults_thenDone() throws Exception {
        stubQueryAndSession();
        Answer<Void> drive = inv -> {
            EventSink sink = inv.getArgument(7);
            sink.emit("intent_extracted", "Sectors: any", Map.of("intent", "x"));
            sink.emit("no_results", "none", Map.of("totalCompanies", 0));
            return null;
        };
        Mockito.doAnswer(drive).when(pipelineService).runSeedListEnhancedStream(
            any(), any(), any(), anyInt(), any(), any(), any(), any());

        MvcResult mvc = perform("asdfqwer", "sess-2");

        String body = mockMvc.perform(asyncDispatch(mvc))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertOrder(body, "search_created", "no_results", "done");
    }

    private static void assertOrder(String body, String... events) {
        int prev = -1;
        for (String ev : events) {
            int idx = body.indexOf("event:" + ev);
            if (idx < 0) idx = body.indexOf("event: " + ev);
            assertThat(idx).as("event %s present and after previous", ev).isGreaterThan(prev);
            prev = idx;
        }
    }
}
