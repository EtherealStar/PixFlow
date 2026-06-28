package com.pixflow.harness.eval.model;

import java.time.Instant;
import java.util.Map;

public record TracePruneEntry(
        Instant recordedAt,
        String phase,
        int tokensBefore,
        int tokensAfter,
        String reason,
        Map<String, Object> metadata) {
    public TracePruneEntry {
        recordedAt = recordedAt == null ? Instant.now() : recordedAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
