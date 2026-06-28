package com.pixflow.infra.vector;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record VectorPoint(String id, float[] vector, Map<String, Object> payload) {
    public VectorPoint {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(vector, "vector must not be null");
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("vector values must be finite");
            }
        }
        vector = Arrays.copyOf(vector, vector.length);
        payload = immutableCopy(payload);
    }

    @Override
    public float[] vector() {
        return Arrays.copyOf(vector, vector.length);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
