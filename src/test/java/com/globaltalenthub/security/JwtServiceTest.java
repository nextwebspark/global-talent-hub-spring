package com.globaltalenthub.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-that-is-at-least-32-bytes-long-for-hs256";

    private JwtService service(String secret) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"test"});
        JwtService s = new JwtService(env);
        ReflectionTestUtils.setField(s, "secret", secret);
        ReflectionTestUtils.setField(s, "expirySeconds", 3600L);
        return s;
    }

    @Test
    void issueThenParse_roundTripsSubjectAndEmail() {
        JwtService svc = service(SECRET);
        UUID userId = UUID.randomUUID();

        String token = svc.issue(userId, "jane@example.com");
        Claims claims = svc.parse(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("email")).isEqualTo("jane@example.com");
        assertThat(claims.getExpiration()).isAfter(new java.util.Date());
    }

    @Test
    void parse_rejectsTokenSignedWithDifferentSecret() {
        JwtService issuer = service(SECRET);
        JwtService other = service("a-completely-different-secret-key-also-32-bytes-plus");

        String token = issuer.issue(UUID.randomUUID(), "x@y.com");

        assertThatThrownBy(() -> other.parse(token))
            .isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void verifySecret_placeholderInNonTestProfile_throws() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        JwtService s = new JwtService(env);
        ReflectionTestUtils.setField(s, "secret", JwtService.PLACEHOLDER_SECRET);

        assertThatThrownBy(s::verifySecret)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APP_JWT_SECRET");
    }

    @Test
    void verifySecret_placeholderInTestProfile_ok() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"test"});
        JwtService s = new JwtService(env);
        ReflectionTestUtils.setField(s, "secret", JwtService.PLACEHOLDER_SECRET);

        s.verifySecret(); // no throw
    }
}
