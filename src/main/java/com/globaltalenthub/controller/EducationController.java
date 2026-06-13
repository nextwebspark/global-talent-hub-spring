package com.globaltalenthub.controller;

import com.globaltalenthub.entity.Education;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.EducationService;
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

/** Education — collection under an executive, items by id. Port of education.ts. */
@RestController
@RequiredArgsConstructor
public class EducationController {

    private final EducationService service;

    @GetMapping("/api/executives/{executiveId}/education")
    public List<Education> list(@PathVariable Long executiveId, @AuthenticationPrincipal AuthenticatedUser user) {
        return service.byExecutive(executiveId, user.orgId());
    }

    @PostMapping("/api/executives/{executiveId}/education")
    @ResponseStatus(HttpStatus.CREATED)
    public Education create(@PathVariable Long executiveId, @RequestBody Education entry,
                            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.create(executiveId, entry, user.orgId());
    }

    @PatchMapping("/api/education/{id}")
    public Education update(@PathVariable Long id, @RequestBody Map<String, Object> patch,
                            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.update(id, patch, user.orgId());
    }

    @DeleteMapping("/api/education/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, user.orgId());
    }
}
