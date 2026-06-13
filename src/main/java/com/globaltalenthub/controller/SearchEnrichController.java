package com.globaltalenthub.controller;

import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.SearchEnrichService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Batch multi-pass enrichment for a project. Port of searchEnrich.ts. */
@RestController
@RequiredArgsConstructor
public class SearchEnrichController {

    private final SearchEnrichService service;

    @PostMapping("/api/search/{id}/enrich-all")
    public Map<String, Object> enrichAll(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return service.enrichAll(id, user.orgId());
    }
}
