package com.globaltalenthub.controller;

import com.globaltalenthub.entity.Executive;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.ExecutiveService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Executive CRUD — org-scoped. Port of the CRUD subset of executives.ts.
 */
@RestController
@RequiredArgsConstructor
public class ExecutiveController {

    private final ExecutiveService executiveService;

    @GetMapping("/api/companies/{companyId}/executives")
    public List<Executive> byCompany(@PathVariable Long companyId, @AuthenticationPrincipal AuthenticatedUser user) {
        return executiveService.getByCompany(companyId, user.orgId());
    }

    @PostMapping("/api/executives")
    @ResponseStatus(HttpStatus.CREATED)
    public Executive create(@RequestBody Executive executive, @AuthenticationPrincipal AuthenticatedUser user) {
        return executiveService.create(executive, user.orgId());
    }

    @PatchMapping("/api/executives/{id}")
    public Executive update(@PathVariable Long id, @RequestBody Map<String, Object> patch,
                            @AuthenticationPrincipal AuthenticatedUser user) {
        return executiveService.updateManual(id, patch, user.orgId());
    }

    @DeleteMapping("/api/executives/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        executiveService.delete(id, user.orgId());
    }
}
