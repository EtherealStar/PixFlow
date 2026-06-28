package com.pixflow.harness.eval.recorder;

import com.pixflow.harness.eval.model.RuntimeScope;
import com.pixflow.harness.eval.model.TraceError;
import com.pixflow.harness.eval.model.TraceInput;
import com.pixflow.harness.eval.model.TracePruneEntry;
import com.pixflow.harness.eval.model.TraceRecall;
import com.pixflow.harness.eval.model.TraceToolCall;
import com.pixflow.harness.eval.model.TurnStatus;
import java.time.Instant;
import java.util.List;

public record TraceCommand(
        String conversationId,
        int turnNo,
        String traceId,
        RuntimeScope runtimeScope,
        TurnStatus status,
        List<TraceInput> inputs,
        List<TraceToolCall> toolCalls,
        List<TraceRecall> recalls,
        List<TracePruneEntry> prunes,
        List<TraceError> errors,
        Instant createdAt,
        Instant updatedAt) {
}
