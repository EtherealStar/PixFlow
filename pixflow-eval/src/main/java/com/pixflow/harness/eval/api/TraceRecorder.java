package com.pixflow.harness.eval.api;

import com.pixflow.harness.eval.model.RuntimeScope;

public interface TraceRecorder {
    TurnTrace begin(String conversationId, int turnNo, String traceId, RuntimeScope runtimeScope);
}
