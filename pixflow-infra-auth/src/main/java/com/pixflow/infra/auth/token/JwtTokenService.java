package com.pixflow.infra.auth.token;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.auth.config.AuthProperties;
import com.pixflow.infra.auth.error.AuthErrorCode;
import com.pixflow.infra.auth.error.AuthException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.util.StringUtils;

public class JwtTokenService {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    private final Clock clock;

    private final String issuer;

    private final byte[] secretBytes;

    private final Duration accessTtl;

    private final Duration clockSkew;

    public JwtTokenService(AuthProperties properties, ObjectMapper objectMapper, Clock clock) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.issuer = requireText(properties.getJwt().getIssuer(), "jwt issuer must not be blank");
        String secret = requireText(properties.getJwt().getSecret(), "jwt secret must not be blank");
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalArgumentException("jwt secret must be at least 32 UTF-8 bytes");
        }
        this.accessTtl = requirePositive(properties.getJwt().getAccessTtl(), "jwt access ttl must be positive");
        this.clockSkew = requireNotNegative(properties.getJwt().getClockSkew(), "jwt clock skew must not be negative");
    }

    public IssuedAccessToken issue(long userId, String username) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("username must not be blank");
        }
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(accessTtl);
        String jwtId = UUID.randomUUID().toString();
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer);
        payload.put("sub", Long.toString(userId));
        payload.put("uname", username);
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());
        payload.put("jti", jwtId);
        payload.put("typ", "access");
        String signingInput = encodeJson(header) + "." + encodeJson(payload);
        return new IssuedAccessToken(signingInput + "." + sign(signingInput), jwtId, expiresAt);
    }

    public AccessTokenClaims parse(String token) {
        if (!StringUtils.hasText(token)) {
            throw new AuthException(AuthErrorCode.AUTH_TOKEN_MISSING, "缺少 access token");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new AuthException(AuthErrorCode.AUTH_TOKEN_INVALID, "access token 格式非法");
        }
        Map<String, Object> header = decodeJson(parts[0], "header");
        requireEquals("HS256", header.get("alg"), AuthErrorCode.AUTH_TOKEN_INVALID, "access token alg 非法");
        requireEquals("JWT", header.get("typ"), AuthErrorCode.AUTH_TOKEN_INVALID, "access token header typ 非法");
        String signingInput = parts[0] + "." + parts[1];
        if (!constantTimeEquals(sign(signingInput), parts[2])) {
            throw new AuthException(AuthErrorCode.AUTH_TOKEN_INVALID, "access token 签名非法");
        }
        Map<String, Object> payload = decodeJson(parts[1], "payload");
        requireEquals("access", payload.get("typ"), AuthErrorCode.AUTH_TOKEN_INVALID, "access token 类型非法");
        requireEquals(issuer, payload.get("iss"), AuthErrorCode.AUTH_TOKEN_INVALID, "access token issuer 非法");

        Instant expiresAt = Instant.ofEpochSecond(asLong(payload.get("exp"), "exp"));
        Instant now = clock.instant();
        if (expiresAt.plus(clockSkew).isBefore(now)) {
            throw new AuthException(AuthErrorCode.AUTH_TOKEN_EXPIRED, "access token 已过期");
        }
        Instant issuedAt = Instant.ofEpochSecond(asLong(payload.get("iat"), "iat"));
        if (!expiresAt.isAfter(issuedAt)) {
            throw new AuthException(AuthErrorCode.AUTH_TOKEN_INVALID, "access token 时间非法");
        }
        return new AccessTokenClaims(
                asLong(payload.get("sub"), "sub"),
                asRequiredString(payload.get("uname"), "uname"),
                asRequiredString(payload.get("jti"), "jti"),
                issuedAt,
                expiresAt);
    }

    public long remainingTtlSeconds(AccessTokenClaims claims) {
        Objects.requireNonNull(claims, "claims must not be null");
        long seconds = claims.expiresAt().getEpochSecond() - clock.instant().getEpochSecond();
        return Math.max(0, seconds);
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encode JWT json", ex);
        }
    }

    private Map<String, Object> decodeJson(String part, String label) {
        try {
            return objectMapper.readValue(URL_DECODER.decode(part), MAP_TYPE);
        } catch (Exception ex) {
            throw new AuthException(AuthErrorCode.AUTH_TOKEN_INVALID, "access token " + label + " 无法解析");
        }
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            return URL_ENCODER.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign JWT", ex);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    private static void requireEquals(Object expected, Object actual, AuthErrorCode code, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AuthException(code, message);
        }
    }

    private static long asLong(Object value, String claim) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ex) {
                throw new AuthException(AuthErrorCode.AUTH_TOKEN_INVALID, "access token claim 非法: " + claim);
            }
        }
        throw new AuthException(AuthErrorCode.AUTH_TOKEN_INVALID, "access token claim 缺失: " + claim);
    }

    private static String asRequiredString(Object value, String claim) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text;
        }
        throw new AuthException(AuthErrorCode.AUTH_TOKEN_INVALID, "access token claim 缺失: " + claim);
    }

    private static String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String message) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static Duration requireNotNegative(Duration value, String message) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
