package com.globaltalenthub.service;

import com.globaltalenthub.entity.OrgMember;
import com.globaltalenthub.entity.Organization;
import com.globaltalenthub.entity.User;
import com.globaltalenthub.entity.UserProfile;
import com.globaltalenthub.repository.OrgMemberRepository;
import com.globaltalenthub.repository.OrganizationRepository;
import com.globaltalenthub.repository.UserProfileRepository;
import com.globaltalenthub.repository.UserRepository;
import com.globaltalenthub.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * App-owned identity: signup (atomic user + org + owner membership), login, and
 * the auth context ({@code me}). Replaces Supabase Auth — passwords are bcrypt
 * hashed here and the JWT is minted by {@link JwtService}.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepo;
    private final OrganizationRepository organizationRepo;
    private final OrgMemberRepository orgMemberRepo;
    private final UserProfileRepository profileRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /** Returned on signup/login: the bearer token + the auth context to seed the client. */
    public record AuthResult(String token, Map<String, Object> user, Organization org, String role) {}

    public record AuthContext(Map<String, Object> user, Organization org, String role,
                              UserProfile profile, Object lastLoginAt) {}

    static String slugify(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /**
     * Atomic signup: create the user (bcrypt password), their organization, an
     * owner membership, and a profile — then mint a token. All-or-nothing.
     */
    @Transactional
    public AuthResult signup(Map<String, Object> body) {
        String email = str(body.get("email")).trim().toLowerCase();
        String password = str(body.get("password"));
        String fullName = str(body.get("name")).trim();

        if (email.isEmpty() || !email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email is required");
        }
        if (password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> org = body.get("org") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String orgName = str(org.get("name")).trim();
        if (orgName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Organization name is required");
        }
        if (userRepo.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        if (!fullName.isEmpty()) user.setFullName(fullName);
        userRepo.save(user);

        Organization organization = createOrg(org, orgName, user.getId());

        OrgMember member = new OrgMember();
        member.setId(UUID.randomUUID());
        member.setOrgId(organization.getId());
        member.setUserId(user.getId());
        member.setEmail(email);
        member.setRole("owner");
        orgMemberRepo.save(member);

        if (!fullName.isEmpty()) {
            UserProfile p = new UserProfile();
            p.setUserId(user.getId());
            p.setFullName(fullName);
            p.setPreferences(new java.util.HashMap<>()); // column is NOT NULL
            profileRepo.save(p);
        }

        String token = jwtService.issue(user.getId(), email);
        return new AuthResult(token, userMap(user.getId(), email), organization, "owner");
    }

    /** Verify credentials and issue a token. Generic message — no account enumeration. */
    public AuthResult login(Map<String, Object> body) {
        String email = str(body.get("email")).trim().toLowerCase();
        String password = str(body.get("password"));

        User user = userRepo.findByEmailIgnoreCase(email).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        OrgMember membership = orgMemberRepo.findByUserId(user.getId()).orElse(null);
        Organization org = membership != null ? organizationRepo.findById(membership.getOrgId()).orElse(null) : null;
        String token = jwtService.issue(user.getId(), user.getEmail());
        return new AuthResult(token, userMap(user.getId(), user.getEmail()), org,
            membership != null ? membership.getRole() : null);
    }

    public AuthContext me(UUID userId, String email) {
        OrgMember membership = orgMemberRepo.findByUserId(userId).orElse(null);
        Organization org = membership != null ? organizationRepo.findById(membership.getOrgId()).orElse(null) : null;
        UserProfile profile = profileRepo.findById(userId).orElse(null);
        String resolvedEmail = email != null ? email
            : (membership != null ? membership.getEmail()
            : userRepo.findById(userId).map(User::getEmail).orElse(null));
        return new AuthContext(userMap(userId, resolvedEmail), org,
            membership != null ? membership.getRole() : null, profile,
            membership != null ? membership.getLastLoginAt() : null);
    }

    private Organization createOrg(Map<String, Object> org, String name, UUID userId) {
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
        return organizationRepo.save(organization);
    }

    private static Map<String, Object> userMap(UUID id, String email) {
        Map<String, Object> user = new java.util.HashMap<>();
        user.put("id", id);
        user.put("email", email);
        return user;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : "";
    }
}
