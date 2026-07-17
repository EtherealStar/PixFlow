package com.pixflow.module.memory.insight;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.ai.embedding.EmbeddingClient;
import com.pixflow.infra.ai.embedding.EmbeddingResult;
import com.pixflow.infra.ai.embedding.EmbeddingVector;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.module.memory.config.MemoryProperties;
import com.pixflow.module.memory.recall.InsightFilter;
import com.pixflow.module.memory.recall.MemoryItem;
import com.pixflow.module.memory.recall.MemoryRanker;
import com.pixflow.module.memory.recall.MemoryType;
import com.pixflow.module.memory.recall.RrfFuser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HybridInsightRecallServiceTest {

    @Test
    void fusesVectorAndKeywordCandidates() {
        HybridInsightRecallService service = service(
                texts -> new EmbeddingResult(List.of(new EmbeddingVector(0, new float[] {1.0f, 0.0f})), new TokenUsage(0, 0, 0)),
                new StubVectorRepo(List.of(item("vector-only", "向量命中：连衣裙主图点击率更高"))),
                (query, filter, topK) -> List.of(item("keyword-only", "关键词命中：白底图适合连衣裙")));

        InsightRecallResult result = service.recall("连衣裙 主图 点击率", InsightFilter.empty(), 10);

        assertThat(result.degraded()).isFalse();
        assertThat(result.items()).extracting(MemoryItem::id).containsExactly("keyword-only", "vector-only");
        assertThat(result.trace()).containsEntry("vector_candidates", 1).containsEntry("keyword_candidates", 1);
    }

    @Test
    void fallsBackToKeywordWhenVectorFails() {
        HybridInsightRecallService service = service(
                texts -> {
                    throw new IllegalStateException("embedding down");
                },
                new StubVectorRepo(List.of()),
                (query, filter, topK) -> List.of(item("keyword", "关键词仍可召回")));

        InsightRecallResult result = service.recall("连衣裙 点击率", InsightFilter.empty(), 10);

        assertThat(result.degraded()).isTrue();
        assertThat(result.items()).extracting(MemoryItem::id).containsExactly("keyword");
        assertThat(result.trace().get("degraded_reasons").toString()).contains("vector_unavailable");
    }

    @Test
    void fallsBackToVectorWhenKeywordFails() {
        HybridInsightRecallService service = service(
                texts -> new EmbeddingResult(List.of(new EmbeddingVector(0, new float[] {1.0f})), new TokenUsage(0, 0, 0)),
                new StubVectorRepo(List.of(item("vector", "向量仍可召回"))),
                (query, filter, topK) -> {
                    throw new IllegalStateException("mysql down");
                });

        InsightRecallResult result = service.recall("连衣裙 点击率", InsightFilter.empty(), 10);

        assertThat(result.degraded()).isTrue();
        assertThat(result.items()).extracting(MemoryItem::id).containsExactly("vector");
        assertThat(result.trace().get("degraded_reasons").toString()).contains("keyword_failed");
    }

    @Test
    void skipsEmbeddingWhenVectorIsNotConfigured() {
        AtomicBoolean embedded = new AtomicBoolean();
        MemoryProperties properties = properties();
        HybridInsightRecallService service = new HybridInsightRecallService(
                texts -> {
                    embedded.set(true);
                    throw new AssertionError("未配置向量维度时不应调用 embedding");
                },
                new StubVectorRepo(List.of()),
                new VectorRecallReadiness(VectorRecallReadiness.Status.NOT_CONFIGURED, "vector_not_configured"),
                (query, filter, topK) -> List.of(item("keyword", "只使用 FULLTEXT")),
                new RrfFuser(),
                ranker(properties),
                properties);

        InsightRecallResult result = service.recall("连衣裙 点击率", InsightFilter.empty(), 10);

        assertThat(embedded).isFalse();
        assertThat(result.items()).extracting(MemoryItem::id).containsExactly("keyword");
        assertThat(result.trace().get("degraded_reasons").toString()).contains("vector_not_configured");
    }

    @Test
    void reportsMissingEmbeddingAsNotConfigured() {
        MemoryProperties properties = properties();
        HybridInsightRecallService service = new HybridInsightRecallService(
                null,
                new StubVectorRepo(List.of()),
                new VectorRecallReadiness(VectorRecallReadiness.Status.READY, ""),
                (query, filter, topK) -> List.of(),
                new RrfFuser(),
                ranker(properties),
                properties);

        InsightRecallResult result = service.recall("连衣裙 点击率", InsightFilter.empty(), 10);

        assertThat(result.trace().get("degraded_reasons")).isEqualTo(List.of("vector_not_configured"));
    }

    @Test
    void returnsEmptyResultWhenBothRecallBranchesFail() {
        HybridInsightRecallService service = service(
                texts -> {
                    throw new IllegalStateException("embedding down");
                },
                new StubVectorRepo(List.of()),
                (query, filter, topK) -> {
                    throw new IllegalStateException("mysql down");
                });

        InsightRecallResult result = service.recall("连衣裙 点击率", InsightFilter.empty(), 10);

        assertThat(result.items()).isEmpty();
        assertThat(result.degraded()).isTrue();
    }

    private static HybridInsightRecallService service(
            EmbeddingClient embeddingClient,
            InsightVectorSearch vectorSearch,
            InsightKeywordSearch keywordSearch) {
        MemoryProperties properties = properties();
        return new HybridInsightRecallService(
                embeddingClient,
                vectorSearch,
                new VectorRecallReadiness(VectorRecallReadiness.Status.READY, ""),
                keywordSearch,
                new RrfFuser(),
                ranker(properties),
                properties);
    }

    private static MemoryProperties properties() {
        MemoryProperties properties = new MemoryProperties();
        properties.getInsight().getRecall().setMinFinalScore(0.0);
        return properties;
    }

    private static MemoryRanker ranker(MemoryProperties properties) {
        return new MemoryRanker(
                properties,
                Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC));
    }

    private static MemoryItem item(String id, String text) {
        return new MemoryItem(id, MemoryType.INSIGHT, text, "test", "连衣裙", "", 0, 0,
                0.8, 0.8, 1.0, Instant.parse("2026-06-27T00:00:00Z"), null, Map.of());
    }

    private record StubVectorRepo(List<MemoryItem> items) implements InsightVectorSearch {
        @Override
        public void verifyCollection(int dimension) {
        }

        @Override
        public List<MemoryItem> search(float[] query, int topK, float threshold, InsightFilter filter) {
            return items;
        }
    }
}
