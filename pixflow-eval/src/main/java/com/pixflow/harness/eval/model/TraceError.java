package com.pixflow.harness.eval.model;

import com.pixflow.common.error.PixFlowException;
import java.time.Instant;
import java.util.Map;

public record TraceError(
        Instant recordedAt,
        String code,
        String category,
        String recovery,
        String message,
        String traceId,
        Map<String, Object> details) {
    public TraceError {
        recordedAt = recordedAt == null ? Instant.now() : recordedAt;
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static TraceError from(PixFlowException error) {
        return new TraceError(
                Instant.now(),
                error.code().code(),
                error.category().name(),
                error.recovery().name(),
                error.getMessage(),
                error.traceId(),
                error.details());
    }
}
