package com.globaltalenthub.controller;

import com.globaltalenthub.entity.Company;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.CompanyService;
import com.globaltalenthub.service.CompanyService.CompanyWithExecutives;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Company CRUD — org-scoped. Returns the flat company JSON (+ executives) the React
 * client expects. Port of routes/registrations/companies.ts.
 */
@RestController
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    // NOTE: the v2 discovery flow lists/searches the app_companies master catalog via
    // AppCompanyController (/api/app/companies/search + /facets), not this per-org hak_companies
    // listing. Still used by the current dashboard UI — kept as-is.
    @GetMapping("/api/companies")
    public List<CompanyWithExecutives> getAll(@AuthenticationPrincipal AuthenticatedUser user) {
        return companyService.getAllWithExecutives(user.orgId());
    }

    // NOTE: v2 company search is AppCompanyController GET /api/app/companies/search (over
    // app_companies). Still used by the current UI (CompanyList / AddCompanyDialog / useImportMode).
    @GetMapping("/api/companies/search")
    public List<Company> search(@RequestParam(name = "name", required = false, defaultValue = "") String name,
                                @AuthenticationPrincipal AuthenticatedUser user) {
        return companyService.searchByName(name, user.orgId());
    }

    // NOTE: v2 single-company read is AppCompanyController GET /api/app/companies/{id} (over
    // app_companies). Still used by the current UI (RightPanel / DataTable) — kept as-is.
    @GetMapping("/api/companies/{id}")
    public CompanyWithExecutives getOne(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return companyService.getWithExecutives(id, user.orgId());
    }

    @PostMapping("/api/companies")
    @ResponseStatus(HttpStatus.CREATED)
    public Company create(@RequestBody Map<String, Object> body, @AuthenticationPrincipal AuthenticatedUser user) {
        return companyService.createManual(body, user.orgId());
    }

    @PatchMapping("/api/companies/{id}")
    public Company update(@PathVariable Long id, @RequestBody Map<String, Object> patch,
                          @AuthenticationPrincipal AuthenticatedUser user) {
        return companyService.updateManual(id, patch, user.orgId());
    }

    @DeleteMapping("/api/companies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        companyService.delete(id, user.orgId());
    }
}
