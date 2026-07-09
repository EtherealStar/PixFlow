package com.pixflow.infra.auth.session;

import java.time.Instant;
import java.util.Objects;

public record AuthSession(
        String refreshJwtId,
        Long userId,
        String username,
        String tokenHash,
        Instant createdAt,
        Instant expiresAt) {
    public AuthSession {
        if (refreshJwtId == null || refreshJwtId.isBlank()) {
            throw new IllegalArgumentException("refreshJwtId must not be blank");
        }
        Objects.requireNonNull(userId, "userId must not be null");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (tokenHash == null || tokenHash.isBlank()) {
            throw new IllegalArgumentException("tokenHash must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }
}
