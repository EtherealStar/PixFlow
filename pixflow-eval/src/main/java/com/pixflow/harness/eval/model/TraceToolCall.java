package com.pixflow.harness.eval.model;

import java.time.Instant;
import java.util.Map;

public record TraceToolCall(
        Instant startedAt,
        String name,
        Map<String, Object> input,
        Object result,
        String resultRef,
        String classification,
        String permissionDecision,
        long latencyMs,
        TraceError error) {
    public TraceToolCall {
        startedAt = startedAt == null ? Instant.now() : startedAt;
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
