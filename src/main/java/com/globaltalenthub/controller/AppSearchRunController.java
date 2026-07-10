package com.globaltalenthub.controller;

import com.globaltalenthub.dto.CreateSearchRunRequest;
import com.globaltalenthub.dto.SearchCriteria;
import com.globaltalenthub.dto.SearchRunDto;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.AppSearchRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * ALAC Talent Map — search runs. Intent extraction (Vertex AI) happens on create;
 * org-scoped. {@code /api/app/**} already requires a valid JWT (SecurityConfig).
 */
@RestController
@RequiredArgsConstructor
public class AppSearchRunController {

    private final AppSearchRunService searchRunService;

    /**
     * PATCH payload — both fields optional. {@code criteria} stores the user's edited filters
     * on the run <b>verbatim</b> (no LLM re-parse); {@code resultCount} is the phase-01 write-back.
     */
    public record PatchSearchRunRequest(Integer resultCount, SearchCriteria criteria) {}

    @PostMapping("/api/app/search-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public SearchRunDto create(@RequestBody CreateSearchRunRequest req,
                               @AuthenticationPrincipal AuthenticatedUser user) {
        return searchRunService.create(req, user);
    }

    @GetMapping("/api/app/search-runs/{id}")
    public SearchRunDto get(@PathVariable Long id,
                            @AuthenticationPrincipal AuthenticatedUser user) {
        return searchRunService.get(id, user);
    }

    @PatchMapping("/api/app/search-runs/{id}")
    public SearchRunDto patch(@PathVariable Long id,
                              @RequestBody PatchSearchRunRequest req,
                              @AuthenticationPrincipal AuthenticatedUser user) {
        return searchRunService.patch(id, req.resultCount(), req.criteria(), user);
    }
}
