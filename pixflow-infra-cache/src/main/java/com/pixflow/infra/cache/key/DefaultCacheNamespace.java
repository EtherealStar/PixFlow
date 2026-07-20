package com.pixflow.infra.cache.key;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/**
 * 默认 key 工厂，强制给所有 key 加上 pixflow:{env}: 前缀。
 */
public final class DefaultCacheNamespace implements CacheNamespace {
    private static final String PREFIX = "pixflow";

    private final String envPrefix;

    private final Duration defaultTtl;

    public DefaultCacheNamespace(String envPrefix, Duration defaultTtl) {
        this.envPrefix = cleanSegment(envPrefix, "envPrefix");
        this.defaultTtl = requirePositive(defaultTtl, "defaultTtl");
    }

    @Override
    public CacheKey key(String... segments) {
        if (segments == null || segments.length == 0) {
            throw new IllegalArgumentException("segments 不能为空");
        }
        String[] cleaned = Arrays.stream(segments)
                .map(segment -> cleanSegment(segment, "segment"))
                .toArray(String[]::new);
        String value = PREFIX + ":" + envPrefix + ":" + String.join(":", cleaned);
        return new CacheKey(value, defaultTtl, cleaned[0]);
    }

    @Override
    public CacheNamespace withDefaultTtl(Duration ttl) {
        return new DefaultCacheNamespace(envPrefix, ttl);
    }

    private static String cleanSegment(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.contains(":") || trimmed.contains("/") || trimmed.contains("\\")) {
            throw new IllegalArgumentException(fieldName + " 不能包含冒号或路径分隔符");
        }
        return trimmed;
    }

    private static Duration requirePositive(Duration duration, String fieldName) {
        Objects.requireNonNull(duration, fieldName);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " 必须为正数");
        }
        return duration;
    }
}
