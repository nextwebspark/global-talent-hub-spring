package com.globaltalenthub.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Issues and parses the application's own HS256 JWTs. Replaces Supabase as the
 * token issuer. Subject = user id (UUID); "email" carried as a claim.
 */
@Service
public class JwtService {

    /** Default in application.properties for tests; must never reach production. */
    public static final String PLACEHOLDER_SECRET = "placeholder-for-tests";

    @Value("${app.jwt.secret:" + PLACEHOLDER_SECRET + "}")
    private String secret;

    /** Token lifetime in seconds (default 7 days). */
    @Value("${app.jwt.expiry-seconds:604800}")
    private long expirySeconds;

    private final Environment environment;

    public JwtService(Environment environment) {
        this.environment = environment;
    }

    // Fail fast: a placeholder secret in a non-test profile means every forged token
    // would validate. Refuse to start.
    @PostConstruct
    void verifySecret() {
        boolean testProfile = List.of(environment.getActiveProfiles()).contains("test");
        if (!testProfile && PLACEHOLDER_SECRET.equals(secret)) {
            throw new IllegalStateException(
                "APP_JWT_SECRET is unset (placeholder default). Set it before starting in a non-test profile.");
        }
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Mint a signed token for the given user. */
    public String issue(UUID userId, String email) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .issuedAt(new Date(now))
            .expiration(new Date(now + expirySeconds * 1000))
            .signWith(key())
            .compact();
    }

    /** Parse + verify a token, returning its claims. Throws JwtException if invalid. */
    public Claims parse(String token) {
        return Jwts.parser()
            .verifyWith(key())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
