package com.pixflow.infra.ai.embedding;

import com.pixflow.infra.ai.model.TokenUsage;
import java.util.List;
import java.util.Objects;

/**
 * embedding 批处理结果。
 */
public record EmbeddingResult(List<EmbeddingVector> vectors, TokenUsage usage) {
    public EmbeddingResult {
        vectors = List.copyOf(Objects.requireNonNull(vectors, "vectors"));
        usage = Objects.requireNonNull(usage, "usage");
    }
}
