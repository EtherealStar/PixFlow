package com.pixflow.infra.vector;

import java.util.Map;
import java.util.Objects;

public record ScoredPoint(String id, float score, Map<String, Object> payload) {
    public ScoredPoint {
        Objects.requireNonNull(id, "id must not be null");
        if (!Float.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
        payload = ImmutablePayload.copy(payload);
    }
}
