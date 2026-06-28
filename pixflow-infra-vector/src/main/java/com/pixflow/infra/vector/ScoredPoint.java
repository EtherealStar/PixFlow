package com.pixflow.infra.vector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ScoredPoint(String id, float score, Map<String, Object> payload) {
    public ScoredPoint {
        Objects.requireNonNull(id, "id must not be null");
        if (!Float.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
        payload = immutableCopy(payload);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
