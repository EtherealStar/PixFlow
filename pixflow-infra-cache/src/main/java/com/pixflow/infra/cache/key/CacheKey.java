package com.pixflow.infra.cache.key;

import java.time.Duration;
import java.util.Objects;

/**
 * Redis key 的不可变载体。
 */
public record CacheKey(String value, Duration suggestedTtl, String namespace) {
    public CacheKey {
        requireText(value, "value");
        Objects.requireNonNull(suggestedTtl, "suggestedTtl");
        requireText(namespace, "namespace");
        if (suggestedTtl.isZero() || suggestedTtl.isNegative()) {
            throw new IllegalArgumentException("suggestedTtl 必须为正数");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }
}
