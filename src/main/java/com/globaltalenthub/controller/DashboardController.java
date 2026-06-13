package com.globaltalenthub.controller;

import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Dashboard analytics. Port of dashboard.ts. */
@RestController
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/api/dashboard/{searchId}")
    public Map<String, Object> dashboard(@PathVariable Long searchId, @AuthenticationPrincipal AuthenticatedUser user) {
        return dashboardService.dashboard(searchId, user.orgId());
    }
}
