package com.pixflow.infra.vector;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** 单点诊断读取的不可变快照。 */
public record VectorPointView(String id, float[] vector, Map<String, Object> payload) {
    public VectorPointView {
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
        payload = ImmutablePayload.copy(payload);
    }

    @Override
    public float[] vector() {
        return Arrays.copyOf(vector, vector.length);
    }
}
