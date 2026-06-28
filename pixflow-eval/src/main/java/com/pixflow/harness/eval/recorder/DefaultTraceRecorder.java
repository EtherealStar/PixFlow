package com.pixflow.harness.eval.recorder;

import com.pixflow.harness.eval.api.TraceRecorder;
import com.pixflow.harness.eval.api.TurnTrace;
import com.pixflow.harness.eval.model.RuntimeScope;
import java.time.Instant;
import java.util.Objects;

public final class DefaultTraceRecorder implements TraceRecorder {
    private final TraceIngestBuffer buffer;

    public DefaultTraceRecorder(TraceIngestBuffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
    }

    @Override
    public TurnTrace begin(String conversationId, int turnNo, String traceId, RuntimeScope runtimeScope) {
        BufferedTurnTrace trace = new BufferedTurnTrace(
                conversationId,
                turnNo,
                traceId,
                runtimeScope == null ? RuntimeScope.MAIN : runtimeScope,
                buffer,
                Instant.now());
        buffer.offer(trace.openCommand());
        return trace;
    }
}
