package com.pixflow.infra.auth.context;

import java.util.Objects;

public record AuthPrincipal(
        Long userId,
        String username,
        String displayName) {
    public AuthPrincipal {
        Objects.requireNonNull(userId, "userId");
        username = requireText(username, "username");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
