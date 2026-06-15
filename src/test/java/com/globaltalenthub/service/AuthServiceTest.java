package com.globaltalenthub.service;

import com.globaltalenthub.entity.OrgMember;
import com.globaltalenthub.entity.Organization;
import com.globaltalenthub.entity.User;
import com.globaltalenthub.repository.OrgMemberRepository;
import com.globaltalenthub.repository.OrganizationRepository;
import com.globaltalenthub.repository.UserProfileRepository;
import com.globaltalenthub.repository.UserRepository;
import com.globaltalenthub.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.globaltalenthub.TestIds.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepo;
    @Mock OrganizationRepository organizationRepo;
    @Mock OrgMemberRepository orgMemberRepo;
    @Mock UserProfileRepository profileRepo;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;

    @InjectMocks AuthService service;

    @Test
    void slugify_normalizes() {
        assertThat(AuthService.slugify("Acme Holdings & Co.")).isEqualTo("acme-holdings-co");
    }

    // ── signup ────────────────────────────────────────────────────────────────

    private Map<String, Object> signupBody() {
        return Map.of(
            "email", "jane@example.com",
            "password", "supersecret",
            "name", "Jane Doe",
            "org", Map.of("name", "Acme Search")
        );
    }

    @Test
    void signup_createsUserOrgOwnerProfileAndToken() {
        when(userRepo.existsByEmailIgnoreCase("jane@example.com")).thenReturn(false);
        when(passwordEncoder.encode("supersecret")).thenReturn("hashed");
        when(organizationRepo.findBySlug(anyString())).thenReturn(Optional.empty());
        when(organizationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jwtService.issue(any(UUID.class), anyString())).thenReturn("token-123");

        var result = service.signup(signupBody());

        assertThat(result.token()).isEqualTo("token-123");
        assertThat(result.role()).isEqualTo("owner");
        assertThat(result.org().getName()).isEqualTo("Acme Search");
        assertThat(result.org().getSlug()).isEqualTo("acme-search");
        verify(userRepo).save(any(User.class));
        verify(orgMemberRepo).save(any(OrgMember.class));
        verify(profileRepo).save(any()); // fullName seeded
    }

    @Test
    void signup_normalizesEmailToLowercase() {
        var body = new java.util.HashMap<>(signupBody());
        body.put("email", "Jane@Example.COM");
        when(userRepo.existsByEmailIgnoreCase("jane@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(organizationRepo.findBySlug(anyString())).thenReturn(Optional.empty());
        when(organizationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jwtService.issue(any(UUID.class), anyString())).thenReturn("t");

        var result = service.signup(body);

        assertThat(result.user().get("email")).isEqualTo("jane@example.com");
    }

    @Test
    void signup_shortPassword_throws400() {
        var body = new java.util.HashMap<>(signupBody());
        body.put("password", "short");

        assertThatThrownBy(() -> service.signup(body))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepo, never()).save(any());
    }

    @Test
    void signup_missingOrgName_throws400() {
        var body = new java.util.HashMap<>(signupBody());
        body.put("org", Map.of());

        assertThatThrownBy(() -> service.signup(body))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void signup_duplicateEmail_throws409() {
        when(userRepo.existsByEmailIgnoreCase("jane@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.signup(signupBody()))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(organizationRepo, never()).save(any());
    }

    @Test
    void signup_slugCollision_appendsSuffix() {
        var body = new java.util.HashMap<>(signupBody());
        body.put("org", Map.of("name", "Acme"));
        when(userRepo.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(organizationRepo.findBySlug("acme")).thenReturn(Optional.of(new Organization()));
        when(organizationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jwtService.issue(any(UUID.class), anyString())).thenReturn("t");

        var result = service.signup(body);

        assertThat(result.org().getSlug()).startsWith("acme-");
        assertThat(result.org().getSlug()).isNotEqualTo("acme");
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returnsTokenAndOrg() {
        UUID userId = uuid("u1");
        User user = new User();
        user.setId(userId);
        user.setEmail("jane@example.com");
        user.setPasswordHash("hashed");
        when(userRepo.findByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("supersecret", "hashed")).thenReturn(true);
        OrgMember member = new OrgMember();
        member.setOrgId(uuid("org-1"));
        member.setRole("owner");
        when(orgMemberRepo.findByUserId(userId)).thenReturn(Optional.of(member));
        when(organizationRepo.findById(uuid("org-1"))).thenReturn(Optional.of(new Organization()));
        when(jwtService.issue(userId, "jane@example.com")).thenReturn("token-xyz");

        var result = service.login(Map.of("email", "jane@example.com", "password", "supersecret"));

        assertThat(result.token()).isEqualTo("token-xyz");
        assertThat(result.role()).isEqualTo("owner");
    }

    @Test
    void login_wrongPassword_throws401() {
        User user = new User();
        user.setPasswordHash("hashed");
        when(userRepo.findByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> service.login(Map.of("email", "jane@example.com", "password", "wrong")))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_unknownEmail_throws401() {
        when(userRepo.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(Map.of("email", "nobody@example.com", "password", "x")))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── me ────────────────────────────────────────────────────────────────────

    @Test
    void me_noOrg_returnsNullOrgAndRole() {
        when(orgMemberRepo.findByUserId(uuid("u1"))).thenReturn(Optional.empty());
        when(profileRepo.findById(uuid("u1"))).thenReturn(Optional.empty());

        var ctx = service.me(uuid("u1"), "u1@example.com");

        assertThat(ctx.org()).isNull();
        assertThat(ctx.role()).isNull();
        assertThat(ctx.user().get("email")).isEqualTo("u1@example.com");
    }

    @Test
    void me_nullEmail_fallsBackToUserRepo() {
        UUID userId = uuid("u1");
        when(orgMemberRepo.findByUserId(userId)).thenReturn(Optional.empty());
        when(profileRepo.findById(userId)).thenReturn(Optional.empty());
        User user = new User();
        user.setEmail("fallback@example.com");
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));

        var ctx = service.me(userId, null);

        assertThat(ctx.user().get("email")).isEqualTo("fallback@example.com");
    }
}
