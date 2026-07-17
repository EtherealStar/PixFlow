package com.pixflow.module.memory.insight;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.vector.VectorException;
import com.pixflow.module.memory.config.MemoryProperties;
import com.pixflow.module.memory.recall.InsightFilter;
import com.pixflow.module.memory.recall.MemoryItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class VectorRecallReadinessTest {
    @Test
    void missingDimensionIsNotConfiguredWithoutProbingQdrant() {
        RecordingSearch search = new RecordingSearch();

        VectorRecallReadiness readiness = VectorRecallReadiness.probe(search, new MemoryProperties());

        assertThat(readiness.status()).isEqualTo(VectorRecallReadiness.Status.NOT_CONFIGURED);
        assertThat(readiness.degradedReason()).isEqualTo("vector_not_configured");
        assertThat(search.verifyCalls).isZero();
    }

    @Test
    void deterministicCollectionFailureDoesNotEscapeStartupProbe() {
        MemoryProperties properties = new MemoryProperties();
        properties.getInsight().setExpectedDimension(3);
        RecordingSearch search = new RecordingSearch();
        search.failure = new VectorException("VERIFY", "insights", false, "dimension mismatch");

        VectorRecallReadiness readiness = VectorRecallReadiness.probe(search, properties);

        assertThat(readiness.status()).isEqualTo(VectorRecallReadiness.Status.UNAVAILABLE);
        assertThat(readiness.degradedReason()).isEqualTo("vector_collection_invalid");
        assertThat(search.verifyCalls).isOne();
    }

    private static class RecordingSearch implements InsightVectorSearch {
        private int verifyCalls;
        private RuntimeException failure;

        @Override
        public void verifyCollection(int dimension) {
            verifyCalls++;
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public List<MemoryItem> search(float[] query, int topK, float threshold, InsightFilter filter) {
            return List.of();
        }
    }
}
