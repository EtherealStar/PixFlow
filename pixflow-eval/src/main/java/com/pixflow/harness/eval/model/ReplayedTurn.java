package com.pixflow.harness.eval.model;

public record ReplayedTurn(
        TurnTraceRecord record,
        String inputJson,
        String toolCallsJson,
        String recallJson,
        String pruneLogJson,
        String errorJson,
        boolean missingExternal) {
}
