package com.pixflow.infra.auth.token;

public record RefreshToken(String token, String jwtId) {
}
