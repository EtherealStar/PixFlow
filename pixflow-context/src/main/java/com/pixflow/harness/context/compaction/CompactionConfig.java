package com.pixflow.harness.context.compaction;

public record CompactionConfig(
        int contextWindowTokens,
        int summaryOutputReservedTokens,
        int autoCompactBufferTokens,
        int tailMaxMessages,
        int maxConsecutiveFailures,
        int maxReactiveRetries) {

    public static CompactionConfig defaults() {
        return new CompactionConfig(128_000, 20_000, 15_000, 20, 3, 1);
    }

    public int autoCompactThreshold() {
        return Math.max(1, contextWindowTokens - summaryOutputReservedTokens - autoCompactBufferTokens);
    }
}
