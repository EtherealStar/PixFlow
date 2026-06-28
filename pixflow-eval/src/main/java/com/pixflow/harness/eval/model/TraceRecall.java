package com.pixflow.harness.eval.model;

import java.time.Instant;
import java.util.Map;

public record TraceRecall(
        Instant recordedAt,
        String source,
        String key,
        double score,
        String preview,
        Map<String, Object> metadata) {
    public TraceRecall {
        recordedAt = recordedAt == null ? Instant.now() : recordedAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
