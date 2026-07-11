package com.pixflow.harness.eval.recorder;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.eval.api.TurnTrace;
import com.pixflow.harness.eval.model.RuntimeScope;
import com.pixflow.harness.eval.model.TraceError;
import com.pixflow.harness.eval.model.TraceInput;
import com.pixflow.harness.eval.model.TracePruneEntry;
import com.pixflow.harness.eval.model.TraceRecall;
import com.pixflow.harness.eval.model.TraceToolCall;
import com.pixflow.harness.eval.model.TurnStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BufferedTurnTrace implements TurnTrace {
    private final String conversationId;
    private final int turnNo;
    private final String traceId;
    private final RuntimeScope runtimeScope;
    private final TraceIngestBuffer buffer;
    private final Instant createdAt;
    private final List<TraceInput> inputs = new ArrayList<>();
    private final List<TraceToolCall> toolCalls = new ArrayList<>();
    private final List<TraceRecall> recalls = new ArrayList<>();
    private final List<TracePruneEntry> prunes = new ArrayList<>();
    private final List<TraceError> errors = new ArrayList<>();
    private final AtomicBoolean completed = new AtomicBoolean();

    public BufferedTurnTrace(
            String conversationId,
            int turnNo,
            String traceId,
            RuntimeScope runtimeScope,
            TraceIngestBuffer buffer,
            Instant createdAt) {
        this.conversationId = conversationId;
        this.turnNo = turnNo;
        this.traceId = traceId;
        this.runtimeScope = runtimeScope;
        this.buffer = buffer;
        this.createdAt = createdAt;
    }

    public TraceCommand openCommand() {
        return snapshot(TurnStatus.OPEN);
    }

    @Override
    public void recordInput(TraceInput input) {
        if (input != null) {
            inputs.add(input);
        }
    }

    @Override
    public void recordToolCall(TraceToolCall call) {
        if (call != null) {
            toolCalls.add(call);
        }
    }

    @Override
    public void recordRecall(TraceRecall recall) {
        if (recall != null) {
            recalls.add(recall);
        }
    }

    @Override
    public void recordPrune(TracePruneEntry entry) {
        if (entry != null) {
            prunes.add(entry);
        }
    }

    @Override
    public void recordError(PixFlowException error) {
        if (error != null) {
            errors.add(TraceError.from(error));
        }
    }

    @Override
    public void commit() {
        if (completed.compareAndSet(false, true)) {
            buffer.offer(snapshot(TurnStatus.COMMITTED));
        }
    }

    @Override
    public void abort(PixFlowException error) {
        recordError(error);
        if (completed.compareAndSet(false, true)) {
            buffer.offer(snapshot(TurnStatus.ABORTED));
        }
    }

    @Override
    public void cancel() {
        if (completed.compareAndSet(false, true)) {
            buffer.offer(snapshot(TurnStatus.CANCELLED));
        }
    }

    private TraceCommand snapshot(TurnStatus status) {
        return new TraceCommand(
                conversationId,
                turnNo,
                traceId,
                runtimeScope,
                status,
                List.copyOf(inputs),
                List.copyOf(toolCalls),
                List.copyOf(recalls),
                List.copyOf(prunes),
                List.copyOf(errors),
                createdAt,
                Instant.now());
    }
}
