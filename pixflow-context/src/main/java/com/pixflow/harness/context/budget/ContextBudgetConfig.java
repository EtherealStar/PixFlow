package com.pixflow.harness.context.budget;

public record ContextBudgetConfig(
        int toolResultExternalizeThresholdBytes,
        int previewChars,
        int snipMaxMessages,
        int microcompactKeepRecentToolResults) {

    public static ContextBudgetConfig defaults() {
        return new ContextBudgetConfig(50 * 1024, 800, 80, 5);
    }
}
