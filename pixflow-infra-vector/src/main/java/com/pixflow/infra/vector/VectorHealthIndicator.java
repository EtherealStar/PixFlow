package com.pixflow.infra.vector;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** Qdrant 传输层健康探针，不向 Spring 容器泄漏原生管理客户端。 */
public final class VectorHealthIndicator implements HealthIndicator {
    private final VectorSearch vectorSearch;

    public VectorHealthIndicator(VectorSearch vectorSearch) {
        this.vectorSearch = vectorSearch;
    }

    @Override
    public Health health() {
        if (!(vectorSearch instanceof QdrantVectorSearch qdrantVectorSearch)) {
            return Health.unknown().withDetail("kind", "NOT_QDRANT_ADAPTER").build();
        }
        try {
            qdrantVectorSearch.healthCheck();
            return Health.up().build();
        } catch (VectorException exception) {
            // 健康端点只暴露稳定分类，避免泄漏凭证、向量或供应商异常细节。
            return Health.down().withDetail("kind", exception.failureKind().name()).build();
        }
    }
}
