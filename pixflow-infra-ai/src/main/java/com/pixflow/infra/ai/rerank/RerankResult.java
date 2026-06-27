package com.pixflow.infra.ai.rerank;

import com.pixflow.infra.ai.model.TokenUsage;
import java.util.List;
import java.util.Objects;

/**
 * 重排结果。
 */
public record RerankResult(List<RerankScore> scores, TokenUsage usage) {
    public RerankResult {
        scores = List.copyOf(Objects.requireNonNull(scores, "scores"));
        usage = Objects.requireNonNull(usage, "usage");
    }
}
