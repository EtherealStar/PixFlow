package com.pixflow.harness.eval.store;

import com.pixflow.harness.eval.model.RuntimeScope;
import com.pixflow.harness.eval.model.TurnStatus;
import java.time.Instant;

public record AgentTraceEntity(
        Long id,
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
