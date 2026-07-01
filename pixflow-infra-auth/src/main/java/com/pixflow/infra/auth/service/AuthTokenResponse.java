package com.pixflow.infra.auth.service;

import java.time.Instant;

public record AuthTokenResponse(
        String accessToken,
        Instant accessTokenExpiresAt,
        UserView user,
        String refreshToken,
        Instant refreshTokenExpiresAt) {
}
