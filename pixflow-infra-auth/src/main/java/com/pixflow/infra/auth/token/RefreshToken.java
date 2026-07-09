package com.pixflow.infra.auth.token;

public record RefreshToken(String token, String jwtId) {
    public RefreshToken {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        if (jwtId == null || jwtId.isBlank()) {
            throw new IllegalArgumentException("jwtId must not be blank");
        }
    }
}
