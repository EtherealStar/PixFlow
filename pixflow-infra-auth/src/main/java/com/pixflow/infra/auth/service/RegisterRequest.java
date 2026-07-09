package com.pixflow.infra.auth.service;

import jakarta.validation.constraints.NotBlank;

public record RegisterRequest(
        @NotBlank String username,
        @NotBlank String password,
        String displayName) {
    @Override
    public String toString() {
        return "RegisterRequest[username=" + username + ", password=<redacted>, displayName=" + displayName + "]";
    }
}
