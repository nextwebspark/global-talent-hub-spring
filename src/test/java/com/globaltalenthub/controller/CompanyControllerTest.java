package com.globaltalenthub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globaltalenthub.entity.Company;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.CompanyService;
import com.globaltalenthub.service.CompanyService.CompanyWithExecutives;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static com.globaltalenthub.TestIds.uuid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CompanyController.class,
    excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
        type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
        classes = {com.globaltalenthub.security.JwtAuthFilter.class,
                   com.globaltalenthub.config.SecurityConfig.class}))
class CompanyControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean CompanyService companyService;

    private static final AuthenticatedUser USER = new AuthenticatedUser(uuid("u1"), "u1@example.com", uuid("org-1"), "admin");

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(USER, null, List.of());
    }

    @Test
    void getAll_returnsCompaniesScopedToOrg() throws Exception {
        Company c = new Company();
        c.setId(1L);
        c.setName("Acme");
        when(companyService.getAllWithExecutives(uuid("org-1")))
            .thenReturn(List.of(new CompanyWithExecutives(c, List.of())));

        mockMvc.perform(get("/api/companies").with(authentication(auth())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].company.name").value("Acme"));

        verify(companyService).getAllWithExecutives(uuid("org-1"));
    }

    @Test
    void create_returns201() throws Exception {
        Company saved = new Company();
        saved.setId(5L);
        saved.setName("NewCo");
        when(companyService.createManual(any(), eq(uuid("org-1")))).thenReturn(saved);

        mockMvc.perform(post("/api/companies")
                .with(authentication(auth()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "NewCo"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("NewCo"));
    }

    @Test
    void getOne_missing_returns404() throws Exception {
        when(companyService.getWithExecutives(eq(99L), eq(uuid("org-1"))))
            .thenThrow(new ResponseStatusException(NOT_FOUND, "Company not found"));

        mockMvc.perform(get("/api/companies/99").with(authentication(auth())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Company not found"));
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/companies/3").with(authentication(auth())).with(csrf()))
            .andExpect(status().isNoContent());
        verify(companyService).delete(3L, uuid("org-1"));
    }
}
