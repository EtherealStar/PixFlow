package com.pixflow.module.task.domain.idempotency;

public record IdempotencyKey(String value) {
    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
        value = value.trim();
        if (value.length() > 128) {
            throw new IllegalArgumentException("idempotency key must not exceed 128 chars");
        }
    }
}
