package com.globaltalenthub.controller;

import com.globaltalenthub.dto.AppCompanyDto;
import com.globaltalenthub.dto.FacetsDto;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.AppCompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ALAC Talent Map — master catalog read endpoints. {@code /api/app/**} is not public
 * in SecurityConfig, so a valid JWT is already required. Reads are not org-filtered
 * (shared master), but auth is enforced.
 */
@RestController
@RequiredArgsConstructor
public class AppCompanyController {

    private final AppCompanyService appCompanyService;

    @GetMapping("/api/app/companies/search")
    public Page<AppCompanyDto> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> industry,
            @RequestParam(required = false) List<String> country,
            @RequestParam(required = false) List<String> revenueRange,
            @RequestParam(required = false) List<String> employeeRange,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) Long projectId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return appCompanyService.search(q, industry, country, revenueRange, employeeRange, sort,
            page, size, projectId, user);
    }

    @GetMapping("/api/app/companies/facets")
    public FacetsDto facets(@AuthenticationPrincipal AuthenticatedUser user) {
        return appCompanyService.facets();
    }

    @GetMapping("/api/app/companies/{id}")
    public AppCompanyDto getOne(@PathVariable Long id,
                                @AuthenticationPrincipal AuthenticatedUser user) {
        return appCompanyService.getById(id);
    }
}
