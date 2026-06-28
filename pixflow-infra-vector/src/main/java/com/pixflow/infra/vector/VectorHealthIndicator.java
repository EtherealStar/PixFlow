package com.pixflow.infra.vector;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

public class VectorHealthIndicator implements HealthIndicator {
    private final QdrantVectorStore vectorStore;

    public VectorHealthIndicator(QdrantVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public Health health() {
        try {
            vectorStore.healthCheck();
            return Health.up().build();
        } catch (RuntimeException ex) {
            return Health.down(ex).build();
        }
    }
}
