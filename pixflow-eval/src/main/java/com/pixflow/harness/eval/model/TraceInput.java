package com.pixflow.harness.eval.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TraceInput(
        Instant recordedAt,
        String stage,
        String promptPreview,
        String messageSnapshotRef,
        List<String> visibleTools,
        Map<String, Object> metadata) {
    public TraceInput {
        recordedAt = recordedAt == null ? Instant.now() : recordedAt;
        visibleTools = visibleTools == null ? List.of() : List.copyOf(visibleTools);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
