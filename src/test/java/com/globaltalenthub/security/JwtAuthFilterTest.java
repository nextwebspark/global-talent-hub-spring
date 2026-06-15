package com.globaltalenthub.security;

import com.globaltalenthub.entity.OrgMember;
import com.globaltalenthub.repository.OrgMemberRepository;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.impl.DefaultClaims;
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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.globaltalenthub.TestIds.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock private JwtService jwtService;
    @Mock private OrgMemberRepository orgMemberRepo;

    @InjectMocks private JwtAuthFilter filter;

    @BeforeEach
    void setup() {
        SecurityContextHolder.clearContext();
    }

    /** Build a Claims object with the given subject + optional email, as JwtService.parse would return. */
    private io.jsonwebtoken.Claims claims(String subject, String email) {
        var m = new java.util.HashMap<String, Object>();
        m.put("sub", subject);
        if (email != null) m.put("email", email);
        return new DefaultClaims(m);
    }

    @Test
    void noToken_chainsWithoutAuthentication() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(res.getStatus()).isEqualTo(200);
        verifyNoInteractions(jwtService);
    }

    @Test
    void validBearerToken_withOrgMembership_setsAuthentication() throws Exception {
        UUID userId = uuid("user-abc");
        when(jwtService.parse("good-token")).thenReturn(claims(userId.toString(), null));
        OrgMember member = new OrgMember();
        member.setUserId(userId);
        member.setOrgId(uuid("org-xyz"));
        member.setRole("admin");
        when(orgMemberRepo.findByUserId(userId)).thenReturn(Optional.of(member));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        req.addHeader("Authorization", "Bearer good-token");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        AuthenticatedUser principal = (AuthenticatedUser) auth.getPrincipal();
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.orgId()).isEqualTo(uuid("org-xyz"));
        assertThat(principal.orgRole()).isEqualTo("admin");
    }

    @Test
    void validQueryParamToken_onSsePath_setsAuthentication() throws Exception {
        UUID userId = uuid("user-sse");
        when(jwtService.parse("sse-token")).thenReturn(claims(userId.toString(), null));
        OrgMember member = new OrgMember();
        member.setUserId(userId);
        member.setOrgId(uuid("org-sse"));
        member.setRole("member");
        when(orgMemberRepo.findByUserId(userId)).thenReturn(Optional.of(member));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/search/enhanced-stream");
        req.addParameter("access_token", "sse-token");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(((AuthenticatedUser) auth.getPrincipal()).userId()).isEqualTo(userId);
    }

    @Test
    void emailClaim_populatedOnPrincipal() throws Exception {
        UUID userId = uuid("user-email");
        when(jwtService.parse("tok")).thenReturn(claims(userId.toString(), "jane@example.com"));
        OrgMember member = new OrgMember();
        member.setUserId(userId);
        member.setOrgId(uuid("org-1"));
        when(orgMemberRepo.findByUserId(userId)).thenReturn(Optional.of(member));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        AuthenticatedUser principal = (AuthenticatedUser)
            SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.email()).isEqualTo("jane@example.com");
    }

    @Test
    void invalidToken_returns401() throws Exception {
        when(jwtService.parse(anyString())).thenThrow(new JwtException("bad"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        req.addHeader("Authorization", "Bearer this-is-not-a-valid-jwt");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validSignature_nonUuidSubject_returns401() throws Exception {
        when(jwtService.parse("tok")).thenReturn(claims("not-a-uuid", null));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validToken_noOrgMembership_orgScopedPath_returns403() throws Exception {
        UUID userId = uuid("orphan-user");
        when(jwtService.parse("tok")).thenReturn(claims(userId.toString(), null));
        when(orgMemberRepo.findByUserId(userId)).thenReturn(Optional.empty());

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        // Tenant isolation: no org membership must not reach an org-scoped endpoint.
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validToken_noOrgMembership_authBootstrapPath_isAllowed() throws Exception {
        UUID userId = uuid("new-user");
        when(jwtService.parse("tok")).thenReturn(claims(userId.toString(), null));
        when(orgMemberRepo.findByUserId(userId)).thenReturn(Optional.empty());

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/me");
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        // Bootstrap path is exempt — a user without an org can call it.
        AuthenticatedUser principal = (AuthenticatedUser)
            SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.orgId()).isNull();
    }

    @Test
    void queryParamToken_onNonSsePath_isIgnored() throws Exception {
        // Token supplied as ?access_token= on a normal API path must NOT authenticate.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/companies");
        req.addParameter("access_token", "tok");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(res.getStatus()).isEqualTo(200);
        verifyNoInteractions(jwtService, orgMemberRepo);
    }
}
