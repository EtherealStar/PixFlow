package com.pixflow.infra.auth.session;

import java.time.Instant;

public record AuthSession(
        String refreshJwtId,
        Long userId,
        String username,
        String tokenHash,
        Instant createdAt,
        Instant expiresAt) {
}
