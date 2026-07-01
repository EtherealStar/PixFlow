package com.pixflow.infra.auth.token;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

public class RefreshTokenGenerator {
    private final SecureRandom random = new SecureRandom();

    public RefreshToken generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return new RefreshToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), UUID.randomUUID().toString());
    }
}
