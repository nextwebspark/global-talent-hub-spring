package com.globaltalenthub.service;

import com.globaltalenthub.entity.LoginEvent;
import com.globaltalenthub.entity.OrgMember;
import com.globaltalenthub.entity.Organization;
import com.globaltalenthub.entity.UserProfile;
import com.globaltalenthub.repository.LoginEventRepository;
import com.globaltalenthub.repository.OrgMemberRepository;
import com.globaltalenthub.repository.OrganizationRepository;
import com.globaltalenthub.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Profile, organization, members, login activity. Port of settings.ts. */
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final List<String> ROLES = List.of("owner", "admin", "member", "viewer");

    private final UserProfileRepository profileRepo;
    private final OrganizationRepository organizationRepo;
    private final OrgMemberRepository orgMemberRepo;
    private final LoginEventRepository loginEventRepo;

    // ── Profile ───────────────────────────────────────────────────────────────
    public UserProfile getProfile(UUID userId) {
        return profileRepo.findById(userId).orElse(null);
    }

    @Transactional
    public UserProfile upsertProfile(UUID userId, Map<String, Object> body) {
        UserProfile p = profileRepo.findById(userId).orElseGet(() -> {
            UserProfile np = new UserProfile();
            np.setUserId(userId);
            return np;
        });
        if (body.get("fullName") instanceof String s) p.setFullName(s);
        if (body.get("jobTitle") instanceof String s) p.setJobTitle(s);
        if (body.get("phone") instanceof String s) p.setPhone(s);
        if (body.get("avatarUrl") instanceof String s) p.setAvatarUrl(s);
        if (body.get("timezone") instanceof String s) p.setTimezone(s);
        if (body.get("language") instanceof String s) p.setLanguage(s);
        if (body.get("preferences") instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> prefs = (Map<String, Object>) m;
            p.setPreferences(prefs);
        }
        return profileRepo.save(p);
    }

    // ── Login activity ──────────────────────────────────────────────────────────
    @Transactional
    public void recordLoginEvent(UUID userId, UUID orgId, String ip, String userAgent) {
        LoginEvent e = new LoginEvent();
        e.setId(UUID.randomUUID());
        e.setUserId(userId);
        e.setOrgId(orgId);
        e.setAt(java.time.LocalDateTime.now());
        e.setIp(ip);
        e.setUserAgent(userAgent);
        loginEventRepo.save(e);
    }

    public List<LoginEvent> loginEvents(UUID userId) {
        return loginEventRepo.findByUserIdOrderByAtDesc(userId).stream().limit(10).toList();
    }

    // ── Organization ─────────────────────────────────────────────────────────────
    public Organization getOrganization(UUID orgId) {
        return organizationRepo.findById(orgId).orElse(null);
    }

    @Transactional
    public Organization updateOrganization(UUID orgId, String orgRole, Map<String, Object> body) {
        requireAdmin(orgRole);
        Organization o = organizationRepo.findById(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));
        if (body.get("name") instanceof String s) o.setName(s);
        if (body.get("region") instanceof String s) o.setRegion(s);
        if (body.get("teamSize") instanceof String s) o.setTeamSize(s);
        if (body.get("logoUrl") instanceof String s) o.setLogoUrl(s);
        if (body.get("defaultRole") instanceof String s) o.setDefaultRole(s);
        if (body.get("require2fa") instanceof Boolean b) o.setRequire2fa(b);
        return organizationRepo.save(o);
    }

    // ── Members ───────────────────────────────────────────────────────────────────
    public List<OrgMember> members(UUID orgId) {
        return orgMemberRepo.findByOrgId(orgId);
    }

    @Transactional
    public OrgMember updateMemberRole(UUID memberId, UUID orgId, String orgRole, String role) {
        requireAdmin(orgRole);
        if (!ROLES.contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role");
        }
        OrgMember member = memberInOrg(memberId, orgId);
        if ("owner".equals(member.getRole()) && !"owner".equals(role)
            && orgMemberRepo.countByOrgIdAndRole(orgId, "owner") <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot demote the only owner");
        }
        member.setRole(role);
        return orgMemberRepo.save(member);
    }

    @Transactional
    public void deleteMember(UUID memberId, UUID orgId, String orgRole) {
        requireAdmin(orgRole);
        OrgMember member = memberInOrg(memberId, orgId);
        if ("owner".equals(member.getRole())
            && orgMemberRepo.countByOrgIdAndRole(orgId, "owner") <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot remove the only owner");
        }
        orgMemberRepo.delete(member);
    }

    private OrgMember memberInOrg(UUID memberId, UUID orgId) {
        OrgMember m = orgMemberRepo.findById(memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        if (!orgId.equals(m.getOrgId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
        }
        return m;
    }

    private static void requireAdmin(String orgRole) {
        if (!"owner".equals(orgRole) && !"admin".equals(orgRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }
}
