package com.pixflow.infra.ai.rerank;

/**
 * 候选项重排得分。
 */
public record RerankScore(int index, double score) {
    public RerankScore {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
    }
}
