package com.pixflow.harness.eval.model;

import java.time.Instant;

public record TurnTraceRecord(
        String conversationId,
        int turnNo,
        String traceId,
        int schemaVersion,
        TurnStatus turnStatus,
        RuntimeScope runtimeScope,
        String inputJson,
        String toolCallsJson,
        String recallJson,
        String pruneLogJson,
        String errorJson,
        Instant createdAt,
        Instant updatedAt) {
}
