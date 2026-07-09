package com.pixflow.infra.auth.token;

import java.time.Instant;
import java.util.Objects;

public record AccessTokenClaims(
        Long userId,
        String username,
        String jwtId,
        Instant issuedAt,
        Instant expiresAt) {
    public AccessTokenClaims {
        Objects.requireNonNull(userId, "userId must not be null");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (jwtId == null || jwtId.isBlank()) {
            throw new IllegalArgumentException("jwtId must not be blank");
        }
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }
}
