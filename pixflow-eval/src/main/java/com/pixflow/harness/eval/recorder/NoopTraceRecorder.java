package com.pixflow.harness.eval.recorder;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.eval.api.TraceRecorder;
import com.pixflow.harness.eval.api.TurnTrace;
import com.pixflow.harness.eval.model.RuntimeScope;
import com.pixflow.harness.eval.model.TraceInput;
import com.pixflow.harness.eval.model.TracePruneEntry;
import com.pixflow.harness.eval.model.TraceRecall;
import com.pixflow.harness.eval.model.TraceToolCall;

public final class NoopTraceRecorder implements TraceRecorder {
    private static final TurnTrace NOOP = new TurnTrace() {
        @Override
        public void recordInput(TraceInput input) {
        }

        @Override
        public void recordToolCall(TraceToolCall call) {
        }

        @Override
        public void recordRecall(TraceRecall recall) {
        }

        @Override
        public void recordPrune(TracePruneEntry entry) {
        }

        @Override
        public void recordError(PixFlowException error) {
        }

        @Override
        public void commit() {
        }

        @Override
        public void abort(PixFlowException error) {
        }

        @Override
        public void cancel() {
        }
    };

    @Override
    public TurnTrace begin(String conversationId, int turnNo, String traceId, RuntimeScope runtimeScope) {
        return NOOP;
    }
}
