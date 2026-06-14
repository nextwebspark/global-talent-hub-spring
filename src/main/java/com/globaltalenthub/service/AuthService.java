package com.globaltalenthub.service;

import com.globaltalenthub.entity.OrgMember;
import com.globaltalenthub.entity.Organization;
import com.globaltalenthub.entity.UserProfile;
import com.globaltalenthub.repository.OrgMemberRepository;
import com.globaltalenthub.repository.OrganizationRepository;
import com.globaltalenthub.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/** Org bootstrap + auth context. Port of auth.ts (signup-org, me). */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final OrganizationRepository organizationRepo;
    private final OrgMemberRepository orgMemberRepo;
    private final UserProfileRepository profileRepo;

    public record SignupResult(Organization org, String role) {}

    public record AuthContext(Map<String, Object> user, Organization org, String role,
                              UserProfile profile, Object lastLoginAt) {}

    static String slugify(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    @Transactional
    public SignupResult signupOrg(UUID userId, String email, Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> org = body.get("org") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String name = org.get("name") instanceof String s ? s.trim() : "";
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Organization name is required");
        }
        if (orgMemberRepo.findByUserId(userId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already has an organization");
        }

        String slugSource = org.get("slug") instanceof String s && !s.isBlank() ? s : name;
        String slug = slugify(slugSource);
        if (organizationRepo.findBySlug(slug).isPresent()) {
            slug = slug + "-" + UUID.randomUUID().toString().substring(0, 4);
        }

        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName(name);
        organization.setSlug(slug);
        if (org.get("teamSize") instanceof String s) organization.setTeamSize(s);
        if (org.get("region") instanceof String s) organization.setRegion(s);
        organization.setCreatedBy(userId);
        if (organization.getDefaultRole() == null) organization.setDefaultRole("member");
        if (organization.getRequire2fa() == null) organization.setRequire2fa(false);
        organizationRepo.save(organization);

        OrgMember member = new OrgMember();
        member.setId(UUID.randomUUID());
        member.setOrgId(organization.getId());
        member.setUserId(userId);
        member.setEmail(email);
        member.setRole("owner");
        orgMemberRepo.save(member);

        String fullName = body.get("name") instanceof String s ? s.trim() : "";
        if (!fullName.isEmpty()) {
            UserProfile p = profileRepo.findById(userId).orElseGet(() -> {
                UserProfile np = new UserProfile();
                np.setUserId(userId);
                return np;
            });
            p.setFullName(fullName);
            profileRepo.save(p);
        }

        return new SignupResult(organization, "owner");
    }

    public AuthContext me(UUID userId, String email) {
        OrgMember membership = orgMemberRepo.findByUserId(userId).orElse(null);
        Organization org = membership != null ? organizationRepo.findById(membership.getOrgId()).orElse(null) : null;
        UserProfile profile = profileRepo.findById(userId).orElse(null);
        Map<String, Object> user = new java.util.HashMap<>();
        user.put("id", userId);
        user.put("email", email != null ? email : (membership != null ? membership.getEmail() : null));
        return new AuthContext(user, org, membership != null ? membership.getRole() : null, profile,
            membership != null ? membership.getLastLoginAt() : null);
    }
}
