package com.pixflow.module.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.ai.embedding.EmbeddingClient;
import com.pixflow.infra.ai.embedding.EmbeddingResult;
import com.pixflow.infra.ai.embedding.EmbeddingVector;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.infra.vector.QdrantVectorStore;
import com.pixflow.infra.vector.VectorProperties;
import com.pixflow.infra.vector.VectorStore;
import com.pixflow.infra.vector.observability.NoopVectorMetrics;
import com.pixflow.module.memory.config.MemoryAutoConfiguration;
import com.pixflow.module.memory.config.MemoryProperties;
import com.pixflow.module.memory.context.MemoryContext;
import com.pixflow.module.memory.context.MemoryContextBuilder;
import com.pixflow.module.memory.context.MemoryContextRequest;
import com.pixflow.module.memory.context.MemorySection;
import com.pixflow.module.memory.insight.AnalysisInsight;
import com.pixflow.module.memory.insight.AnalysisInsightStatus;
import com.pixflow.module.memory.insight.DefaultInsightVectorRepo;
import com.pixflow.module.memory.insight.InsightDocMapper;
import com.pixflow.module.memory.insight.InsightLifecycleService;
import com.pixflow.module.memory.insight.InsightVectorRepo;
import com.pixflow.module.memory.preference.UserPreference;
import com.pixflow.module.memory.preference.UserPreferenceMapper;
import com.pixflow.module.memory.recall.InsightFilter;
import com.pixflow.module.memory.recall.MemoryItem;
import com.pixflow.module.memory.skuhistory.SkuHistory;
import com.pixflow.module.memory.skuhistory.SkuHistoryMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = MemoryMySqlQdrantIntegrationTest.TestApp.class,
        properties = {
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:schema.sql",
                "pixflow.memory.insight.collection=memory_it_analysis_insight",
                "pixflow.memory.insight.recall.min-final-score=0",
                "pixflow.memory.insight.recall.vector-threshold=0",
                "pixflow.memory.insight.lifecycle.expire-threshold=0.80",
                "pixflow.memory.insight.lifecycle.suppress-threshold=0.90"
        })
class MemoryMySqlQdrantIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pixflow_memory")
            .withUsername("pixflow")
            .withPassword("pixflow");

    @Container
    private static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.18.2")
            .withExposedPorts(6334);

    private static QdrantClient qdrantClient;

    @Autowired
    private MemoryService memoryService;
    @Autowired
    private UserPreferenceMapper preferenceMapper;
    @Autowired
    private SkuHistoryMapper skuHistoryMapper;
    @Autowired
    private InsightDocMapper insightMapper;
    @Autowired
    private InsightVectorRepo vectorRepo;
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private MemoryProperties memoryProperties;
    @Autowired
    private InsightLifecycleService lifecycleService;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @AfterAll
    static void closeQdrantClient() {
        if (qdrantClient != null) {
            qdrantClient.close();
        }
    }

    @Test
    void preparesContextFromRealMySqlFulltextAndQdrantVectorIndex() {
        seedPreference("background", "white");
        seedSkuHistory("SKU123", "task-1");
        AnalysisInsight keywordAndVector = seedInsight(
                "夏季连衣裙白底主图点击率高于场景图。",
                "连衣裙",
                "SKU123",
                "keyword-vector",
                Instant.parse("2026-06-28T00:00:00Z"));
        AnalysisInsight vectorOnly = seedInsight(
                "模特半身构图能提升女装主图点击表现。",
                "连衣裙",
                "SKU123",
                "vector-only",
                Instant.parse("2026-06-27T00:00:00Z"));
        seedInsight(
                "低饱和蓝色背景适合家居类 SKU，提高停留时长。",
                "家居",
                "SKU999",
                "filtered-out",
                Instant.parse("2026-06-26T00:00:00Z"));

        vectorRepo.ensureCollection(3);
        vectorRepo.upsertActive(keywordAndVector, embedding("连衣裙 白底 主图 点击率"));
        vectorRepo.upsertActive(vectorOnly, embedding("连衣裙 主图 点击率"));

        MemoryContext context = memoryService.prepareContext(new MemoryContextRequest(
                "conversation-1",
                1,
                "trace-1",
                "帮我处理 SKU123 的连衣裙主图，优先提升点击率",
                List.of(),
                null,
                "task-1",
                List.of(),
                List.of(),
                Map.of(),
                null));

        MemorySection preference = context.section(MemoryContextBuilder.USER_PREFERENCES);
        MemorySection skuHistory = context.section(MemoryContextBuilder.SKU_HISTORY);
        MemorySection insights = context.section(MemoryContextBuilder.ANALYSIS_INSIGHTS);

        assertThat(preference.renderedText()).contains("background=white");
        assertThat(skuHistory.renderedText()).contains("SKU SKU123 历史处理 task=task-1");
        assertThat(insights.items()).extracting(MemoryItem::id)
                .contains(String.valueOf(keywordAndVector.getId()), String.valueOf(vectorOnly.getId()));
        assertThat(insights.renderedText()).contains("夏季连衣裙白底主图点击率高于场景图", "模特半身构图");
        assertThat(insights.renderedText()).doesNotContain("家居类");
        assertThat(context.recallTrace().toString()).contains("vector_candidates", "keyword_candidates");
    }

    @Test
    void lifecycleExpiresMySqlInsightAndDeletesQdrantPoint() {
        AnalysisInsight stale = seedInsight(
                "过期活动期主图结论不应再被召回。",
                "连衣裙",
                "SKU123",
                "stale",
                Instant.parse("2020-01-01T00:00:00Z"));
        vectorRepo.ensureCollection(3);
        vectorRepo.upsertActive(stale, embedding("过期 活动 主图"));
        String pointId = DefaultInsightVectorRepo.vectorPointId(String.valueOf(stale.getId()));

        assertThat(vectorStore.get(memoryProperties.getInsight().getCollection(), pointId)).isPresent();

        lifecycleService.maintain();

        AnalysisInsight updated = insightMapper.selectById(stale.getId());
        assertThat(updated.getStatus()).isIn(AnalysisInsightStatus.SUPPRESSED, AnalysisInsightStatus.EXPIRED);
        assertThat(vectorStore.get(memoryProperties.getInsight().getCollection(), pointId)).isEmpty();
        assertThat(insightMapper.fulltextSearch("过期 活动 主图", InsightFilter.empty(), 5)).isEmpty();
    }

    private void seedPreference(String key, String value) {
        UserPreference preference = new UserPreference();
        preference.setKey(key);
        preference.setValue(value);
        preference.setUpdatedAt(Instant.parse("2026-06-28T00:00:00Z"));
        preferenceMapper.insert(preference);
    }

    private void seedSkuHistory(String skuId, String taskId) {
        SkuHistory history = new SkuHistory();
        history.setSkuId(skuId);
        history.setTaskId(taskId);
        history.setParamsJson("{\"background\":\"white\"}");
        history.setMetricsBefore("{\"ctr\":0.02}");
        history.setMetricsAfter("{\"ctr\":0.04}");
        history.setRubricsScore(new BigDecimal("88.0"));
        history.setCreatedAt(Instant.parse("2026-06-28T00:00:00Z"));
        skuHistoryMapper.insert(history);
    }

    private AnalysisInsight seedInsight(String text, String category, String skuId, String source, Instant createdAt) {
        AnalysisInsight insight = new AnalysisInsight();
        insight.setText(text);
        insight.setCategory(category);
        insight.setSource(source);
        insight.setConfidence(0.9);
        insight.setRelatedSku(skuId);
        insight.setContentHash(contentHash(text, source));
        insight.setImportance(0.8);
        insight.setStatus(AnalysisInsightStatus.ACTIVE);
        insight.setAccessCount(0);
        insight.setDecayScore(1.0);
        insight.setCreatedAt(createdAt);
        insight.setUpdatedAt(createdAt);
        if ("stale".equals(source)) {
            insight.setExpiresAt(Instant.parse("2020-01-02T00:00:00Z"));
        }
        insightMapper.insert(insight);
        return insight;
    }

    private static float[] embedding(String text) {
        String normalized = text == null ? "" : text;
        if (normalized.contains("家居")) {
            return new float[] {0.0f, 1.0f, 0.0f};
        }
        if (normalized.contains("过期") || normalized.contains("活动")) {
            return new float[] {0.0f, 0.0f, 1.0f};
        }
        return new float[] {1.0f, 0.0f, 0.0f};
    }

    private static String contentHash(String text, String source) {
        String value = Integer.toHexString(text.hashCode()) + Integer.toHexString(source.hashCode());
        return (value + "00000000000000000000000000000000").substring(0, 32);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MemoryAutoConfiguration.class)
    static class TestApp {
        @Bean
        EmbeddingClient embeddingClient() {
            return texts -> new EmbeddingResult(
                    java.util.stream.IntStream.range(0, texts.size())
                            .mapToObj(index -> new EmbeddingVector(index, embedding(texts.get(index))))
                            .toList(),
                    new TokenUsage(0, 0, 0));
        }

        @Bean
        VectorStore vectorStore() {
            VectorProperties properties = new VectorProperties();
            properties.setHost(QDRANT.getHost());
            properties.setPort(QDRANT.getMappedPort(6334));
            properties.setTimeout(Duration.ofSeconds(10));
            qdrantClient = new QdrantClient(QdrantGrpcClient.newBuilder(
                    properties.getHost(),
                    properties.getPort(),
                    false).build());
            return new QdrantVectorStore(qdrantClient, properties, new NoopVectorMetrics());
        }

        @Bean
        InsightVectorRepo insightVectorRepo(VectorStore vectorStore, MemoryProperties memoryProperties) {
            return new DefaultInsightVectorRepo(vectorStore, memoryProperties);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
