package com.pixflow.harness.eval.api;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.eval.model.TraceInput;
import com.pixflow.harness.eval.model.TracePruneEntry;
import com.pixflow.harness.eval.model.TraceRecall;
import com.pixflow.harness.eval.model.TraceToolCall;

public interface TurnTrace extends AutoCloseable {
    void recordInput(TraceInput input);

    void recordToolCall(TraceToolCall call);

    void recordRecall(TraceRecall recall);

    void recordPrune(TracePruneEntry entry);

    void recordError(PixFlowException error);

    void commit();

    void abort(PixFlowException error);

    void cancel();

    @Override
    default void close() {
        commit();
    }
}
