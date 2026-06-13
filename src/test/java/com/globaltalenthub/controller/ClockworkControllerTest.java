package com.globaltalenthub.controller;

import com.globaltalenthub.repository.SearchQueryRepository;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.SearchQueryService;
import com.globaltalenthub.service.clockwork.ClockworkApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** S1 tenant-isolation tests for Clockwork endpoints. */
@WebMvcTest(controllers = ClockworkController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
        classes = {com.globaltalenthub.security.SupabaseJwtFilter.class,
                   com.globaltalenthub.config.SecurityConfig.class}))
class ClockworkControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ClockworkApiClient clockwork;
    @MockBean SearchQueryService searchQueryService;
    @MockBean SearchQueryRepository searchQueryRepo;

    private UsernamePasswordAuthenticationToken auth(String role) {
        return new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser("u1", "u1@example.com", "org-1", role), null, List.of());
    }

    @Test
    void projects_adminAllowed() throws Exception {
        when(clockwork.getProjects()).thenReturn(List.of());
        mockMvc.perform(get("/api/clockwork/projects").with(authentication(auth("admin"))))
            .andExpect(status().isOk());
    }

    @Test
    void projects_nonAdmin_forbidden() throws Exception {
        mockMvc.perform(get("/api/clockwork/projects").with(authentication(auth("member"))))
            .andExpect(status().isForbidden());
        verify(clockwork, never()).getProjects();
    }

    @Test
    void people_projectNotLinkedToOrg_forbidden() throws Exception {
        when(searchQueryRepo.existsByClockworkProjectIdAndOrgId("proj-9", "org-1")).thenReturn(false);

        mockMvc.perform(get("/api/clockwork/projects/proj-9/people").with(authentication(auth("member"))))
            .andExpect(status().isForbidden());
        verify(clockwork, never()).getProjectPeople(anyString());
    }

    @Test
    void people_projectLinkedToOrg_allowed() throws Exception {
        when(searchQueryRepo.existsByClockworkProjectIdAndOrgId("proj-1", "org-1")).thenReturn(true);
        when(clockwork.getProjectPeople("proj-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/clockwork/projects/proj-1/people").with(authentication(auth("member"))))
            .andExpect(status().isOk());
        verify(clockwork).getProjectPeople("proj-1");
    }

    @Test
    void diagnostics_noTenantData_allowedForAnyAuthed() throws Exception {
        when(clockwork.isConfigured()).thenReturn(true);
        mockMvc.perform(get("/api/clockwork/diagnostics").with(authentication(auth("member"))))
            .andExpect(status().isOk());
    }
}
