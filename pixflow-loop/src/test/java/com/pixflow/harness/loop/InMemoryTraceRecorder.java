package com.pixflow.harness.loop;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.eval.api.TraceRecorder;
import com.pixflow.harness.eval.api.TurnTrace;
import com.pixflow.harness.eval.model.RuntimeScope;
import com.pixflow.harness.eval.model.TraceError;
import com.pixflow.harness.eval.model.TraceInput;
import com.pixflow.harness.eval.model.TracePruneEntry;
import com.pixflow.harness.eval.model.TraceRecall;
import com.pixflow.harness.eval.model.TraceToolCall;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试用 {@link TraceRecorder}：记录每次 begin 调用，并产出可断言的 InMemoryTurnTrace。
 */
public final class InMemoryTraceRecorder implements TraceRecorder {

    private final List<InMemoryTurnTrace> traces = Collections.synchronizedList(new ArrayList<>());

    @Override
    public TurnTrace begin(String conversationId, int turnNo, String traceId, RuntimeScope runtimeScope) {
        InMemoryTurnTrace t = new InMemoryTurnTrace(conversationId, turnNo, traceId, runtimeScope);
        traces.add(t);
        return t;
    }

    public List<InMemoryTurnTrace> traces() {
        synchronized (traces) {
            return new ArrayList<>(traces);
        }
    }

    public static final class InMemoryTurnTrace implements TurnTrace {

        private final String conversationId;
        private final int turnNo;
        private final String traceId;
        private final RuntimeScope runtimeScope;
        private final List<TraceInput> inputs = new ArrayList<>();
        private final List<TraceToolCall> toolCalls = new ArrayList<>();
        private final List<TraceRecall> recalls = new ArrayList<>();
        private final List<TracePruneEntry> prunes = new ArrayList<>();
        private final List<TraceError> errors = new ArrayList<>();
        private boolean committed;
        private boolean aborted;
        private boolean cancelled;

        InMemoryTurnTrace(String conversationId, int turnNo, String traceId, RuntimeScope runtimeScope) {
            this.conversationId = conversationId;
            this.turnNo = turnNo;
            this.traceId = traceId;
            this.runtimeScope = runtimeScope;
        }

        @Override
        public void close() {
            // abort 后 close 不应覆盖状态
            if (!aborted && !committed && !cancelled) {
                commit();
            }
        }

        public String conversationId() { return conversationId; }
        public int turnNo() { return turnNo; }
        public String traceId() { return traceId; }
        public RuntimeScope runtimeScope() { return runtimeScope; }
        public List<TraceInput> inputs() { return List.copyOf(inputs); }
        public List<TraceToolCall> toolCalls() { return List.copyOf(toolCalls); }
        public List<TraceRecall> recalls() { return List.copyOf(recalls); }
        public List<TracePruneEntry> prunes() { return List.copyOf(prunes); }
        public List<TraceError> errors() { return List.copyOf(errors); }
        public boolean committed() { return committed; }
        public boolean aborted() { return aborted; }
        public boolean cancelled() { return cancelled; }

        @Override public void recordInput(TraceInput input) { if (input != null) inputs.add(input); }
        @Override public void recordToolCall(TraceToolCall call) { if (call != null) toolCalls.add(call); }
        @Override public void recordRecall(TraceRecall recall) { if (recall != null) recalls.add(recall); }
        @Override public void recordPrune(TracePruneEntry entry) { if (entry != null) prunes.add(entry); }
        @Override public void recordError(PixFlowException error) {
            if (error != null) errors.add(TraceError.from(error));
        }
        @Override public void commit() { if (!aborted) committed = true; }
        @Override public void abort(PixFlowException error) {
            recordError(error);
            aborted = true;
            committed = false;
        }
        @Override public void cancel() {
            cancelled = true;
            committed = false;
            aborted = false;
        }
    }
}
