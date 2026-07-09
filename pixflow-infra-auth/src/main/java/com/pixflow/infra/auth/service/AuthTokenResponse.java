package com.pixflow.infra.auth.service;

import java.time.Instant;

public record AuthTokenResponse(
        String accessToken,
        Instant accessTokenExpiresAt,
        UserView user,
        String refreshToken,
        Instant refreshTokenExpiresAt) {
    @Override
    public String toString() {
        return "AuthTokenResponse[accessToken=<redacted>, accessTokenExpiresAt="
                + accessTokenExpiresAt
                + ", user="
                + user
                + ", refreshToken=<redacted>, refreshTokenExpiresAt="
                + refreshTokenExpiresAt
                + "]";
    }
}
