package com.pixflow.module.vision.api;

public record ReanalyzeVisualFactsCommand(long expectedGeneration, String requestId) {
    public ReanalyzeVisualFactsCommand {
        if (expectedGeneration < 0) {
            throw new IllegalArgumentException("expectedGeneration must be non-negative");
        }
        requestId = requestId == null ? null : requestId.strip();
        if (requestId == null || requestId.isEmpty() || requestId.length() > 128) {
            throw new IllegalArgumentException("requestId must contain 1 to 128 characters");
        }
    }
}
