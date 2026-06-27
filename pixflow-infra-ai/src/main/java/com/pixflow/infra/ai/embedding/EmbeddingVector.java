package com.pixflow.infra.ai.embedding;

/**
 * 单条 embedding 向量。
 */
public record EmbeddingVector(int index, float[] values) {
    public EmbeddingVector {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("values must not be empty");
        }
        values = values.clone();
    }

    @Override
    public float[] values() {
        return values.clone();
    }
}
