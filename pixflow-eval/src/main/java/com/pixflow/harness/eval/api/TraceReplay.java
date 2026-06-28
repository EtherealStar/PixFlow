package com.pixflow.harness.eval.api;

import com.pixflow.harness.eval.model.ReplayedTurn;

public interface TraceReplay {
    ReplayedTurn replay(String conversationId, int turnNo);
}
