package com.pixflow.infra.auth.token;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.auth.config.AuthProperties;
import com.pixflow.infra.auth.error.AuthErrorCode;
import com.pixflow.infra.auth.error.AuthException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.util.StringUtils;

public class JwtTokenService {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AuthProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JwtTokenService(AuthProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public IssuedAccessToken issue(long userId, String username) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.getJwt().getAccessTtl());
        String jwtId = UUID.randomUUID().toString();
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", properties.getJwt().getIssuer());
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
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new AuthException(AuthErrorCode.AUTH_TOKEN_INVALID, "access token 格式非法");
        }
        String signingInput = parts[0] + "." + parts[1];
        if (!constantTimeEquals(sign(signingInput), parts[2])) {
            throw new AuthException(AuthErrorCode.AUTH_TOKEN_INVALID, "access token 签名非法");
        }
        Map<String, Object> payload = decodePayload(parts[1]);
        requireEquals("access", payload.get("typ"), AuthErrorCode.AUTH_TOKEN_INVALID, "access token 类型非法");
        requireEquals(properties.getJwt().getIssuer(), payload.get("iss"), AuthErrorCode.AUTH_TOKEN_INVALID, "access token issuer 非法");

        Instant expiresAt = Instant.ofEpochSecond(asLong(payload.get("exp"), "exp"));
        Instant now = clock.instant();
        if (expiresAt.plus(properties.getJwt().getClockSkew()).isBefore(now)) {
            throw new AuthException(AuthErrorCode.AUTH_TOKEN_EXPIRED, "access token 已过期");
        }
        Instant issuedAt = Instant.ofEpochSecond(asLong(payload.get("iat"), "iat"));
        return new AccessTokenClaims(
                Long.parseLong(String.valueOf(payload.get("sub"))),
                String.valueOf(payload.get("uname")),
                String.valueOf(payload.get("jti")),
                issuedAt,
                expiresAt);
    }

    public long remainingTtlSeconds(AccessTokenClaims claims) {
        long seconds = claims.expiresAt().getEpochSecond() - clock.instant().getEpochSecond();
        return Math.max(1, seconds);
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encode JWT json", ex);
        }
    }

    private Map<String, Object> decodePayload(String payloadPart) {
        try {
            return objectMapper.readValue(URL_DECODER.decode(payloadPart), MAP_TYPE);
        } catch (Exception ex) {
            throw new AuthException(AuthErrorCode.AUTH_TOKEN_INVALID, "access token payload 无法解析");
        }
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
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
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            throw new AuthException(code, message);
        }
    }

    private static long asLong(Object value, String claim) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new AuthException(AuthErrorCode.AUTH_TOKEN_INVALID, "access token claim 非法: " + claim);
        }
    }
}
