package com.globaltalenthub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.globaltalenthub.entity.SearchQuery;
import com.globaltalenthub.repository.SearchQueryRepository;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.SearchQueryService;
import com.globaltalenthub.service.clockwork.ClockworkApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Clockwork read endpoints + project linkage. Port of clockwork.ts.
 *
 * <p>Tenant isolation (S1): the shared firm-wide Clockwork API must not leak across orgs.
 * Project browsing is admin-only (integration setup); reading a project's people requires
 * that project to be linked to one of the caller's org's searches.
 */
@RestController
@RequiredArgsConstructor
public class ClockworkController {

    private final ClockworkApiClient clockwork;
    private final SearchQueryService searchQueryService;
    private final SearchQueryRepository searchQueryRepo;

    @GetMapping("/api/clockwork/diagnostics")
    public Map<String, Object> diagnostics() {
        // Config status only — no tenant data.
        return Map.of(
            "ok", clockwork.isConfigured(),
            "configured", clockwork.isConfigured(),
            "message", clockwork.isConfigured() ? "Clockwork configured" : "Clockwork credentials not configured");
    }

    /** Browse firm projects to link one to a search — admin only (integration setup). */
    @GetMapping("/api/clockwork/projects")
    public List<JsonNode> projects(@AuthenticationPrincipal AuthenticatedUser user) {
        requireAdmin(user);
        return clockwork.getProjects();
    }

    /** People for a project — only if that project is linked to the caller's org. */
    @GetMapping("/api/clockwork/projects/{projectId}/people")
    public Map<String, Object> people(@PathVariable String projectId,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        if (!searchQueryRepo.existsByClockworkProjectIdAndOrgId(projectId, user.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Clockwork project not linked to your organization");
        }
        List<JsonNode> people = clockwork.getProjectPeople(projectId);
        return Map.of("projectId", projectId, "count", people.size(), "people", people);
    }

    @PatchMapping("/api/search/{searchId}/name")
    public SearchQuery rename(@PathVariable Long searchId, @RequestBody Map<String, Object> body,
                              @AuthenticationPrincipal AuthenticatedUser user) {
        String name = body.get("name") == null ? null : body.get("name").toString();
        return searchQueryService.rename(searchId, name, user.orgId());
    }

    @PatchMapping("/api/search/{searchId}/clockwork-project")
    public SearchQuery linkProject(@PathVariable Long searchId, @RequestBody Map<String, Object> body,
                                   @AuthenticationPrincipal AuthenticatedUser user) {
        String projectId = body.get("clockworkProjectId") == null ? null : body.get("clockworkProjectId").toString();
        return searchQueryService.setClockworkProject(searchId, projectId, user.orgId());
    }

    private static void requireAdmin(AuthenticatedUser user) {
        String role = user.orgRole();
        if (!"owner".equals(role) && !"admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }
}
