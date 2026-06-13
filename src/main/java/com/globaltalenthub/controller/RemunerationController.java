package com.globaltalenthub.controller;

import com.globaltalenthub.entity.Remuneration;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.RemunerationService;
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

/** Remuneration — collection under an executive, items by id. Port of remuneration.ts. */
@RestController
@RequiredArgsConstructor
public class RemunerationController {

    private final RemunerationService service;

    @GetMapping("/api/executives/{executiveId}/remuneration")
    public List<Remuneration> list(@PathVariable Long executiveId, @AuthenticationPrincipal AuthenticatedUser user) {
        return service.byExecutive(executiveId, user.orgId());
    }

    @PostMapping("/api/executives/{executiveId}/remuneration")
    @ResponseStatus(HttpStatus.CREATED)
    public Remuneration create(@PathVariable Long executiveId, @RequestBody Remuneration entry,
                               @AuthenticationPrincipal AuthenticatedUser user) {
        return service.create(executiveId, entry, user.orgId());
    }

    @PatchMapping("/api/remuneration/{id}")
    public Remuneration update(@PathVariable Long id, @RequestBody Map<String, Object> patch,
                               @AuthenticationPrincipal AuthenticatedUser user) {
        return service.update(id, patch, user.orgId());
    }

    @DeleteMapping("/api/remuneration/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, user.orgId());
    }
}
