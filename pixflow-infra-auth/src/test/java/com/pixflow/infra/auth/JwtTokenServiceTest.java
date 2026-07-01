package com.pixflow.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.auth.config.AuthProperties;
import com.pixflow.infra.auth.error.AuthErrorCode;
import com.pixflow.infra.auth.error.AuthException;
import com.pixflow.infra.auth.token.IssuedAccessToken;
import com.pixflow.infra.auth.token.JwtTokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {
    @Test
    void issueAndParseAccessToken() {
        AuthProperties properties = properties(Duration.ofMinutes(15));
        JwtTokenService service = new JwtTokenService(
                properties,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC));

        IssuedAccessToken issued = service.issue(42L, "alice");

        assertThat(service.parse(issued.token()).userId()).isEqualTo(42L);
        assertThat(service.parse(issued.token()).username()).isEqualTo("alice");
        assertThat(service.parse(issued.token()).jwtId()).isEqualTo(issued.jwtId());
    }

    @Test
    void rejectsExpiredAccessToken() {
        AuthProperties properties = properties(Duration.ofSeconds(1));
        JwtTokenService issuer = new JwtTokenService(
                properties,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC));
        IssuedAccessToken issued = issuer.issue(42L, "alice");

        JwtTokenService parser = new JwtTokenService(
                properties,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-01T00:01:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> parser.parse(issued.token()))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_TOKEN_EXPIRED));
    }

    @Test
    void rejectsTamperedSignature() {
        JwtTokenService service = new JwtTokenService(
                properties(Duration.ofMinutes(15)),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC));
        IssuedAccessToken issued = service.issue(42L, "alice");

        assertThatThrownBy(() -> service.parse(issued.token() + "x"))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_TOKEN_INVALID));
    }

    private static AuthProperties properties(Duration accessTtl) {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setSecret("test-secret-with-more-than-32-bytes");
        properties.getJwt().setAccessTtl(accessTtl);
        properties.getJwt().setClockSkew(Duration.ZERO);
        return properties;
    }
}
