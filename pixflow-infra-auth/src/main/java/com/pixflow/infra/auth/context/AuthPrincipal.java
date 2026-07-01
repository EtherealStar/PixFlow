package com.pixflow.infra.auth.context;

import java.util.List;
import java.util.Objects;

public record AuthPrincipal(
        Long userId,
        String username,
        String displayName,
        String status,
        List<String> authorities) {
    public AuthPrincipal {
        Objects.requireNonNull(userId, "userId");
        username = requireText(username, "username");
        status = requireText(status, "status");
        authorities = authorities == null ? List.of() : List.copyOf(authorities);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
