package com.pixflow.infra.auth.token;

import java.time.Instant;

public record IssuedAccessToken(String token, String jwtId, Instant expiresAt) {
}
