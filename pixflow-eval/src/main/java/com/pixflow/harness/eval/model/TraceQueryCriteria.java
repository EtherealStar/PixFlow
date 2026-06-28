package com.pixflow.harness.eval.model;

import java.time.Instant;

public record TraceQueryCriteria(
        Instant from,
        Instant to,
        String conversationId,
        String traceId,
        TurnStatus turnStatus,
        RuntimeScope runtimeScope,
        String toolName,
        Boolean hasError,
        Boolean hasPrune) {
}
