package com.globaltalenthub.security;

import com.globaltalenthub.entity.OrgMember;
import com.globaltalenthub.repository.OrgMemberRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static com.globaltalenthub.TestIds.uuid;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupabaseJwtFilterTest {

    private static final String JWT_SECRET = "test-secret-key-that-is-at-least-32-bytes-long-for-hs256";

    @Mock
    private OrgMemberRepository orgMemberRepo;

    @Mock
    private org.springframework.core.env.Environment environment;

    @InjectMocks
    private SupabaseJwtFilter filter;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(filter, "jwtSecret", JWT_SECRET);
        SecurityContextHolder.clearContext();
    }

    private String buildToken(String subject) {
        return buildToken(subject, null);
    }

    private String buildToken(String subject, String email) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        var builder = Jwts.builder()
            .subject(subject)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + 3_600_000));
        if (email != null) builder.claim("email", email);
        return builder.signWith(key).compact();
    }

    @Test
    void noToken_chainsWithoutAuthentication() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void validBearerToken_withOrgMembership_setsAuthentication() throws Exception {
        UUID userId = uuid("user-abc");
        OrgMember member = new OrgMember();
        member.setUserId(userId);
        member.setOrgId(uuid("org-xyz"));
        member.setRole("admin");

        when(orgMemberRepo.findByUserId(userId)).thenReturn(Optional.of(member));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        req.addHeader("Authorization", "Bearer " + buildToken(userId.toString()));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        AuthenticatedUser principal = (AuthenticatedUser) auth.getPrincipal();
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.orgId()).isEqualTo(uuid("org-xyz"));
        assertThat(principal.orgRole()).isEqualTo("admin");
    }

    @Test
    void validQueryParamToken_withOrg_setsAuthentication() throws Exception {
        UUID userId = uuid("user-sse");
        OrgMember member = new OrgMember();
        member.setUserId(userId);
        member.setOrgId(uuid("org-sse"));
        member.setRole("member");
        when(orgMemberRepo.findByUserId(userId)).thenReturn(Optional.of(member));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/search/enhanced-stream");
        req.addParameter("access_token", buildToken(userId.toString()));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        AuthenticatedUser principal = (AuthenticatedUser) auth.getPrincipal();
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.orgId()).isEqualTo(uuid("org-sse"));
    }

    @Test
    void emailClaim_populatedOnPrincipal() throws Exception {
        UUID userId = uuid("user-email");
        OrgMember member = new OrgMember();
        member.setUserId(userId);
        member.setOrgId(uuid("org-1"));
        when(orgMemberRepo.findByUserId(userId)).thenReturn(Optional.of(member));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        req.addHeader("Authorization", "Bearer " + buildToken(userId.toString(), "jane@example.com"));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        AuthenticatedUser principal = (AuthenticatedUser)
            SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.email()).isEqualTo("jane@example.com");
    }

    @Test
    void invalidToken_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        req.addHeader("Authorization", "Bearer this-is-not-a-valid-jwt");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validSignature_nonUuidSubject_returns401() throws Exception {
        // Signature valid but 'sub' is not a UUID -> UUID.fromString throws -> 401.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        req.addHeader("Authorization", "Bearer " + buildToken("not-a-uuid"));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validToken_noOrgMembership_orgScopedPath_returns403() throws Exception {
        UUID userId = uuid("orphan-user");
        when(orgMemberRepo.findByUserId(userId)).thenReturn(Optional.empty());

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        req.addHeader("Authorization", "Bearer " + buildToken(userId.toString()));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        // Tenant isolation: no org membership must not reach an org-scoped endpoint.
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validToken_noOrgMembership_authBootstrapPath_isAllowed() throws Exception {
        UUID userId = uuid("new-user");
        when(orgMemberRepo.findByUserId(userId)).thenReturn(Optional.empty());

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/me");
        req.addHeader("Authorization", "Bearer " + buildToken(userId.toString()));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        // Bootstrap path is exempt — a user without an org can call it to create one.
        AuthenticatedUser principal = (AuthenticatedUser)
            SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.orgId()).isNull();
    }

    @Test
    void verifySecret_placeholderInNonTestProfile_throws() {
        SupabaseJwtFilter f = new SupabaseJwtFilter(orgMemberRepo, environment);
        ReflectionTestUtils.setField(f, "jwtSecret", "placeholder-for-tests");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        assertThatThrownBy(f::verifySecret)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SUPABASE_JWT_SECRET");
    }

    @Test
    void verifySecret_placeholderInTestProfile_ok() {
        SupabaseJwtFilter f = new SupabaseJwtFilter(orgMemberRepo, environment);
        ReflectionTestUtils.setField(f, "jwtSecret", "placeholder-for-tests");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});

        f.verifySecret(); // no throw
    }

    // ── S4: query-param token only on the SSE route ──────────────────────────────
    @Test
    void queryParamToken_onNonSsePath_isIgnored() throws Exception {
        // Token supplied as ?access_token= on a normal API path must NOT authenticate.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        req.addParameter("access_token", buildToken("user-x"));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(res.getStatus()).isEqualTo(200); // chained through unauthenticated
        verifyNoInteractions(orgMemberRepo);
    }

    // ── S5: audience binding ──────────────────────────────────────────────────────
    @Test
    void wrongAudience_isRejected() throws Exception {
        ReflectionTestUtils.setField(filter, "jwtAudience", "authenticated");

        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder().subject("user-aud").audience().add("some-other-app").and()
            .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 3_600_000))
            .signWith(key).compact();

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void correctAudience_isAccepted() throws Exception {
        ReflectionTestUtils.setField(filter, "jwtAudience", "authenticated");
        UUID userId = uuid("user-aud2");
        OrgMember member = new OrgMember();
        member.setUserId(userId);
        member.setOrgId(uuid("org-1"));
        when(orgMemberRepo.findByUserId(userId)).thenReturn(Optional.of(member));

        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder().subject(userId.toString()).audience().add("authenticated").and()
            .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 3_600_000))
            .signWith(key).compact();

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(((AuthenticatedUser) auth.getPrincipal()).userId()).isEqualTo(userId);
    }
}
