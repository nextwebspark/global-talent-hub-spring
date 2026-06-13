package com.globaltalenthub.controller;

import com.globaltalenthub.entity.LoginEvent;
import com.globaltalenthub.entity.OrgMember;
import com.globaltalenthub.entity.Organization;
import com.globaltalenthub.entity.UserProfile;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Profile, login activity, organization, members. Port of settings.ts. */
@RestController
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService service;

    @GetMapping("/api/profile")
    public Map<String, UserProfile> getProfile(@AuthenticationPrincipal AuthenticatedUser user) {
        return mapOf("profile", service.getProfile(user.userId()));
    }

    @PutMapping("/api/profile")
    public Map<String, UserProfile> putProfile(@RequestBody Map<String, Object> body,
                                               @AuthenticationPrincipal AuthenticatedUser user) {
        return mapOf("profile", service.upsertProfile(user.userId(), body));
    }

    @PostMapping("/api/auth/login-event")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void loginEvent(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
        String forwarded = request.getHeader("x-forwarded-for");
        String ip = forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        service.recordLoginEvent(user.userId(), user.orgId(), ip, request.getHeader("user-agent"));
    }

    @GetMapping("/api/auth/login-events")
    public Map<String, List<LoginEvent>> loginEvents(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("events", service.loginEvents(user.userId()));
    }

    @GetMapping("/api/org")
    public Map<String, Organization> getOrg(@AuthenticationPrincipal AuthenticatedUser user) {
        return mapOf("org", service.getOrganization(user.orgId()));
    }

    @PutMapping("/api/org")
    public Map<String, Organization> putOrg(@RequestBody Map<String, Object> body,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("org", service.updateOrganization(user.orgId(), user.orgRole(), body));
    }

    @GetMapping("/api/org/members")
    public Map<String, List<OrgMember>> members(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("members", service.members(user.orgId()));
    }

    @PatchMapping("/api/org/members/{id}")
    public Map<String, OrgMember> updateMember(@PathVariable String id, @RequestBody Map<String, Object> body,
                                               @AuthenticationPrincipal AuthenticatedUser user) {
        String role = body.get("role") == null ? "" : body.get("role").toString();
        return Map.of("member", service.updateMemberRole(id, user.orgId(), user.orgRole(), role));
    }

    @DeleteMapping("/api/org/members/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMember(@PathVariable String id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.deleteMember(id, user.orgId(), user.orgRole());
    }

    // Helper: Map.of rejects null values; build a HashMap so a null profile/org serializes as null.
    private static <V> Map<String, V> mapOf(String key, V value) {
        Map<String, V> m = new java.util.HashMap<>();
        m.put(key, value);
        return m;
    }
}
