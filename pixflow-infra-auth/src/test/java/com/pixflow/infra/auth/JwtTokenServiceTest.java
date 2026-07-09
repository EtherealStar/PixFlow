package com.pixflow.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.auth.config.AuthProperties;
import com.pixflow.infra.auth.error.AuthErrorCode;
import com.pixflow.infra.auth.error.AuthException;
import com.pixflow.infra.auth.token.AccessTokenClaims;
import com.pixflow.infra.auth.token.IssuedAccessToken;
import com.pixflow.infra.auth.token.JwtTokenService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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

    @Test
    void rejectsUnexpectedHeaderAlgorithm() {
        AuthProperties properties = properties(Duration.ofMinutes(15));
        JwtTokenService service = new JwtTokenService(
                properties,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC));
        Map<String, Object> header = Map.of("alg", "none", "typ", "JWT");
        Map<String, Object> payload = validPayload();

        assertThatThrownBy(() -> service.parse(signToken(header, payload, properties.getJwt().getSecret())))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_TOKEN_INVALID));
    }

    @Test
    void rejectsMissingRequiredClaimsAsAuthException() {
        AuthProperties properties = properties(Duration.ofMinutes(15));
        JwtTokenService service = new JwtTokenService(
                properties,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC));
        Map<String, Object> payload = validPayload();
        payload.remove("uname");

        assertThatThrownBy(() -> service.parse(signToken(Map.of("alg", "HS256", "typ", "JWT"), payload, properties.getJwt().getSecret())))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_TOKEN_INVALID));
    }

    @Test
    void rejectsInvalidTimeOrder() {
        AuthProperties properties = properties(Duration.ofMinutes(15));
        JwtTokenService service = new JwtTokenService(
                properties,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC));
        Map<String, Object> payload = validPayload();
        payload.put("exp", payload.get("iat"));

        assertThatThrownBy(() -> service.parse(signToken(Map.of("alg", "HS256", "typ", "JWT"), payload, properties.getJwt().getSecret())))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_TOKEN_INVALID));
    }

    @Test
    void remainingTtlReturnsZeroForExpiredClaims() {
        JwtTokenService service = new JwtTokenService(
                properties(Duration.ofMinutes(15)),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-01T00:02:00Z"), ZoneOffset.UTC));
        AccessTokenClaims claims = new AccessTokenClaims(
                42L,
                "alice",
                "jwt-id",
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:01:00Z"));

        assertThat(service.remainingTtlSeconds(claims)).isZero();
    }

    private static AuthProperties properties(Duration accessTtl) {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setSecret("test-secret-with-more-than-32-bytes");
        properties.getJwt().setAccessTtl(accessTtl);
        properties.getJwt().setClockSkew(Duration.ZERO);
        return properties;
    }

    private static Map<String, Object> validPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", "pixflow");
        payload.put("sub", "42");
        payload.put("uname", "alice");
        payload.put("iat", Instant.parse("2026-07-01T00:00:00Z").getEpochSecond());
        payload.put("exp", Instant.parse("2026-07-01T00:15:00Z").getEpochSecond());
        payload.put("jti", "jwt-id");
        payload.put("typ", "access");
        return payload;
    }

    private static String signToken(Map<String, Object> header, Map<String, Object> payload, String secret) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String signingInput = encoder.encodeToString(mapper.writeValueAsBytes(header))
                    + "."
                    + encoder.encodeToString(mapper.writeValueAsBytes(payload));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return signingInput + "." + encoder.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
