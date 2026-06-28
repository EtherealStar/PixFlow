package com.pixflow.module.memory.lifecycle;

import java.time.Instant;
import java.util.Map;

public record MemoryReinforcementEvent(
        String insightId,
        String reason,
        double confidenceDelta,
        double importanceDelta,
        Instant occurredAt,
        Map<String, Object> metadata) {

    public MemoryReinforcementEvent {
        if (insightId == null || insightId.isBlank()) {
            throw new IllegalArgumentException("insightId must not be blank");
        }
        reason = reason == null ? "" : reason;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
