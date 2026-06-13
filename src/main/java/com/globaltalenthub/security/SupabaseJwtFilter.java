package com.globaltalenthub.security;

import com.globaltalenthub.entity.OrgMember;
import com.globaltalenthub.repository.OrgMemberRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SupabaseJwtFilter extends OncePerRequestFilter {

    /** Default in application.yml for tests; must never reach production. */
    private static final String PLACEHOLDER_SECRET = "placeholder-for-tests";

    /** Paths reachable without an org (auth bootstrap + public config/health). */
    private static final List<String> ORG_EXEMPT_PREFIXES = List.of("/api/auth/", "/api/config", "/api/health");

    /** Only this route may carry the JWT as a query param (EventSource can't set headers). */
    private static final String SSE_PATH = "/api/search/enhanced-stream";

    @Value("${app.supabase.jwt-secret}")
    private String jwtSecret;

    /** Pinned audience (Supabase standard "authenticated"). Empty disables the check. */
    @Value("${app.supabase.jwt-audience:authenticated}")
    private String jwtAudience;

    /** Pinned issuer (e.g. https://<project>.supabase.co/auth/v1). Empty = not enforced. */
    @Value("${app.supabase.jwt-issuer:}")
    private String jwtIssuer;

    private final OrgMemberRepository orgMemberRepo;
    private final Environment environment;

    // Fail fast: a placeholder secret in a non-test profile means every forged token
    // would validate. Refuse to start.
    @PostConstruct
    void verifySecret() {
        boolean testProfile = List.of(environment.getActiveProfiles()).contains("test");
        if (!testProfile && PLACEHOLDER_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                "SUPABASE_JWT_SECRET is unset (placeholder default). Set it before starting in a non-test profile.");
        }
    }

    private String extractToken(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        // Query-param token is accepted ONLY on the SSE route — EventSource can't set
        // headers. Restricting it elsewhere keeps JWTs out of general access logs / history.
        if (SSE_PATH.equals(req.getRequestURI())) {
            String qp = req.getParameter("access_token");
            return (qp != null && !qp.isBlank()) ? qp : null;
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            var parser = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)));
            // Bind to expected audience/issuer so a token signed with the same secret but
            // from a different project / of a different type is rejected (not just valid sig).
            if (jwtAudience != null && !jwtAudience.isBlank()) parser.requireAudience(jwtAudience);
            if (jwtIssuer != null && !jwtIssuer.isBlank()) parser.requireIssuer(jwtIssuer);
            Claims claims = parser.build()
                .parseSignedClaims(token)
                .getPayload();

            String userId = claims.getSubject();
            Object emailClaim = claims.get("email");
            String email = emailClaim != null ? emailClaim.toString() : null;
            OrgMember membership = orgMemberRepo.findByUserId(userId).orElse(null);
            String orgId = membership != null ? membership.getOrgId() : null;

            // Tenant isolation: a valid token without org membership must not reach
            // org-scoped endpoints (where orgId=null would leak org_id IS NULL rows).
            if (orgId == null && !isOrgExempt(request)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "No organization membership");
                return;
            }

            AuthenticatedUser principal = new AuthenticatedUser(
                userId,
                email,
                orgId,
                membership != null ? membership.getRole() : null
            );

            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of())
            );
        } catch (JwtException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
            return;
        }

        chain.doFilter(request, response);
    }

    private static boolean isOrgExempt(HttpServletRequest req) {
        String path = req.getRequestURI();
        return ORG_EXEMPT_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
