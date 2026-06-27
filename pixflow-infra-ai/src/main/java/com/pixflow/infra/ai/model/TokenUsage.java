package com.pixflow.infra.ai.model;

/**
 * 供应商回传的真实 token 用量。
 */
public record TokenUsage(long promptTokens, long completionTokens, long totalTokens) {
    public TokenUsage {
        if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token usage must be non-negative");
        }
    }
}
