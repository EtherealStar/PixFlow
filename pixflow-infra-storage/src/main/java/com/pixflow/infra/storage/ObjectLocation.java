package com.pixflow.infra.storage;

import java.util.Objects;

/**
 * 对象的逻辑位置：逻辑桶 + 桶内 key。
 */
public record ObjectLocation(BucketType bucket, String key) {

    public ObjectLocation {
        bucket = Objects.requireNonNull(bucket, "bucket must not be null");
        key = normalizeKey(key);
    }

    public static ObjectLocation of(BucketType bucket, String key) {
        return new ObjectLocation(bucket, key);
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        String normalized = key.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (normalized.startsWith("../")
                || normalized.contains("/../")
                || normalized.endsWith("/..")
                || normalized.equals("..")) {
            throw new IllegalArgumentException("key must not contain path traversal");
        }
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("key must not contain path traversal");
        }
        if (normalized.contains("//")) {
            normalized = collapseRepeatedSlash(normalized);
        }
        if (normalized.startsWith("/") || normalized.contains(":")) {
            throw new IllegalArgumentException("key must not be an absolute path");
        }
        return normalized;
    }

    private static String collapseRepeatedSlash(String value) {
        String result = value;
        while (result.contains("//")) {
            result = result.replace("//", "/");
        }
        return result;
    }
}
