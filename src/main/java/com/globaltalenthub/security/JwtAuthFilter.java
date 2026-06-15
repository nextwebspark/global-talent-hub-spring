package com.globaltalenthub.security;

import com.globaltalenthub.entity.OrgMember;
import com.globaltalenthub.repository.OrgMemberRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Validates the application's own JWT (issued by {@link JwtService}) and attaches
 * the authenticated principal. Replaces the former Supabase-token filter.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Paths reachable without an org (auth bootstrap + public config/health). */
    private static final List<String> ORG_EXEMPT_PREFIXES = List.of("/api/auth/", "/api/config", "/api/health");

    /** Only this route may carry the JWT as a query param (EventSource can't set headers). */
    private static final String SSE_PATH = "/api/search/enhanced-stream";

    private final JwtService jwtService;
    private final OrgMemberRepository orgMemberRepo;

    // SSE (SseEmitter) completes via an ASYNC dispatch, on which Spring Security
    // re-runs the filter chain. OncePerRequestFilter skips async dispatches by
    // default, so without this the re-dispatch has no authentication and the
    // AuthorizationFilter denies it (AccessDenied after the stream finishes).
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
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
            Claims claims = jwtService.parse(token);

            UUID userId = UUID.fromString(claims.getSubject());
            Object emailClaim = claims.get("email");
            String email = emailClaim != null ? emailClaim.toString() : null;
            OrgMember membership = orgMemberRepo.findByUserId(userId).orElse(null);
            UUID orgId = membership != null ? membership.getOrgId() : null;

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
        } catch (JwtException | IllegalArgumentException e) {
            // IllegalArgumentException: malformed 'sub' claim (not a UUID).
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
