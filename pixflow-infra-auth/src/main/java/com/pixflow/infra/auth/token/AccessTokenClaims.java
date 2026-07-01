package com.pixflow.infra.auth.token;

import java.time.Instant;

public record AccessTokenClaims(
        Long userId,
        String username,
        String jwtId,
        Instant issuedAt,
        Instant expiresAt) {
}
