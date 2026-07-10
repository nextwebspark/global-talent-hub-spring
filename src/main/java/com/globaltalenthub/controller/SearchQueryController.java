package com.globaltalenthub.controller;

import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.SearchQueryService;
import com.globaltalenthub.service.SearchQueryService.DraftResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Project (search-query) management — clear results, save draft, bulk delete. Port of searchQueries.ts. */
// NOTE: v2 persists a confirmed universe as an app_projects "search map" via AppProjectController
// (/api/app/projects); this hak-era search-query persistence is superseded there. Still used by the
// current UI (ProjectsPage / UniversePage / ProjectsPanel) — kept as-is.
@RestController
@RequiredArgsConstructor
public class SearchQueryController {

    private final SearchQueryService service;

    @DeleteMapping("/api/search-queries/{id}/results")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearResults(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.deleteResults(id, user.orgId());
    }

    @PatchMapping("/api/search-queries/{id}/draft")
    public DraftResult saveDraft(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return service.updateDraft(id, body, user.orgId());
    }

    @PostMapping("/api/search-queries/bulk-delete")
    public Map<String, Integer> bulkDelete(@RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) body.get("ids");
        List<Long> ids = rawIds == null ? null : rawIds.stream().map(o -> ((Number) o).longValue()).toList();
        return Map.of("deleted", service.bulkDelete(ids, user.orgId()));
    }
}
