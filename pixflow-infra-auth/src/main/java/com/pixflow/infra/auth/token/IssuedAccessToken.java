package com.pixflow.infra.auth.token;

import java.time.Instant;
import java.util.Objects;

public record IssuedAccessToken(String token, String jwtId, Instant expiresAt) {
    public IssuedAccessToken {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        if (jwtId == null || jwtId.isBlank()) {
            throw new IllegalArgumentException("jwtId must not be blank");
        }
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
