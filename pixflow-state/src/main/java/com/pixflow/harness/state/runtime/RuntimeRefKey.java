package com.pixflow.harness.state.runtime;

import java.time.Duration;

/** 运行态引用键；由 state 适配器在内部转换为具体缓存键。 */
public record RuntimeRefKey(String value, Duration ttl, String namespace) {
    public RuntimeRefKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
    }
}
