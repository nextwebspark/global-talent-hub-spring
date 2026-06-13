package com.globaltalenthub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globaltalenthub.entity.Executive;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.ExecutiveService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExecutiveController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
        classes = {com.globaltalenthub.security.SupabaseJwtFilter.class,
                   com.globaltalenthub.config.SecurityConfig.class}))
class ExecutiveControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean ExecutiveService executiveService;

    private static final AuthenticatedUser USER = new AuthenticatedUser("u1", "u1@example.com", "org-1", "admin");

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(USER, null, List.of());
    }

    @Test
    void byCompany_returnsList() throws Exception {
        Executive e = new Executive();
        e.setId(1L);
        e.setName("Jane CFO");
        when(executiveService.getByCompany(10L, "org-1")).thenReturn(List.of(e));

        mockMvc.perform(get("/api/companies/10/executives").with(authentication(auth())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Jane CFO"));
    }

    @Test
    void create_returns201() throws Exception {
        Executive saved = new Executive();
        saved.setId(2L);
        saved.setName("New Exec");
        when(executiveService.create(any(), eq("org-1"))).thenReturn(saved);

        mockMvc.perform(post("/api/executives").with(authentication(auth())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "New Exec", "companyId", 10))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("New Exec"));
    }

    @Test
    void patch_delegatesWithOrg() throws Exception {
        Executive updated = new Executive();
        updated.setId(3L);
        when(executiveService.updateManual(eq(3L), any(), eq("org-1"))).thenReturn(updated);

        mockMvc.perform(patch("/api/executives/3").with(authentication(auth())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("title", "CEO"))))
            .andExpect(status().isOk());

        verify(executiveService).updateManual(eq(3L), any(), eq("org-1"));
    }

    @Test
    void byCompany_notInOrg_returns404() throws Exception {
        when(executiveService.getByCompany(eq(99L), eq("org-1")))
            .thenThrow(new ResponseStatusException(NOT_FOUND, "Company not found"));

        mockMvc.perform(get("/api/companies/99/executives").with(authentication(auth())))
            .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/executives/5").with(authentication(auth())).with(csrf()))
            .andExpect(status().isNoContent());
        verify(executiveService).delete(5L, "org-1");
    }
}
