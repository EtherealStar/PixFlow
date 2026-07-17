package com.pixflow.module.memory.insight;

import com.pixflow.infra.vector.VectorException;
import com.pixflow.module.memory.config.MemoryProperties;
import java.util.Objects;

public record VectorRecallReadiness(Status status, String degradedReason) {
    public enum Status {
        READY,
        NOT_CONFIGURED,
        UNAVAILABLE
    }

    public VectorRecallReadiness {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(degradedReason, "degradedReason");
    }

    public static VectorRecallReadiness probe(InsightVectorSearch search, MemoryProperties properties) {
        Integer dimension = properties.getInsight().getExpectedDimension();
        if (search == null || dimension == null) {
            return new VectorRecallReadiness(Status.NOT_CONFIGURED, "vector_not_configured");
        }
        try {
            search.verifyCollection(dimension);
            return new VectorRecallReadiness(Status.READY, "");
        } catch (VectorException ex) {
            String reason = ex.failureKind() == VectorException.FailureKind.DEPENDENCY
                    ? "vector_unavailable"
                    : "vector_collection_invalid";
            return new VectorRecallReadiness(Status.UNAVAILABLE, reason);
        } catch (RuntimeException ex) {
            return new VectorRecallReadiness(Status.UNAVAILABLE, "vector_unavailable");
        }
    }

    public boolean ready() {
        return status == Status.READY;
    }
}
