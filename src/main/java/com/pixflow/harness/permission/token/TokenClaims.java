package com.pixflow.harness.permission.token;

import java.time.Instant;
import java.util.Objects;

/**
 * 令牌绑定的确认载荷。
 */
public record TokenClaims(
        ConfirmationAction action,
        String conversationId,
        String packageId,
        String payloadHash,
        ConfirmationLevel level,
        int expectedCount,
        Instant issuedAt,
        Instant expiresAt,
        String nonce) {

    public TokenClaims {
        Objects.requireNonNull(action, "action");
        requireText(conversationId, "conversationId");
        requireText(packageId, "packageId");
        requireText(payloadHash, "payloadHash");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        requireText(nonce, "nonce");
        if (expectedCount < 0) {
            throw new IllegalArgumentException("expectedCount 不能小于 0");
        }
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt 必须晚于 issuedAt");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }
}
