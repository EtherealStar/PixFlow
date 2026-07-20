package com.pixflow.module.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.ai.config.AiAutoConfiguration;
import com.pixflow.infra.ai.embedding.EmbeddingClient;
import com.pixflow.infra.ai.embedding.EmbeddingResult;
import com.pixflow.infra.ai.embedding.EmbeddingVector;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.infra.vector.QdrantVectorSearch;
import com.pixflow.infra.vector.VectorProperties;
import com.pixflow.infra.vector.VectorSearch;
import com.pixflow.infra.vector.observability.NoopVectorMetrics;
import com.pixflow.module.memory.config.MemoryAutoConfiguration;
import com.pixflow.module.memory.context.MemoryContext;
import com.pixflow.module.memory.context.MemoryContextBuilder;
import com.pixflow.module.memory.context.MemoryContextRequest;
import com.pixflow.module.memory.insight.AnalysisInsight;
import com.pixflow.module.memory.insight.AnalysisInsightStatus;
import com.pixflow.module.memory.insight.InsightDocMapper;
import com.pixflow.module.memory.recall.MemoryItem;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
                "pixflow.memory.insight.expected-dimension=3",
                "pixflow.memory.insight.recall.min-final-score=0",
                "pixflow.memory.insight.recall.vector-threshold=0"
        })
class MemoryMySqlQdrantIntegrationTest {
    private static final String COLLECTION = "memory_it_analysis_insight";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pixflow_memory")
            .withUsername("pixflow")
            .withPassword("pixflow");

    @Container
    private static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.18.2")
            .withExposedPorts(6334);

    private static QdrantClient adminClient;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private InsightDocMapper insightMapper;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @AfterAll
    static void closeAdminClient() throws Exception {
        if (adminClient != null) {
            adminClient.deleteCollectionAsync(COLLECTION, Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS);
            adminClient.close();
        }
    }

    @Test
    void combinesFulltextAndReadOnlyVectorRecallWithoutMutatingEitherStore() throws Exception {
        AnalysisInsight keywordAndVector = seedInsight(
                "夏季连衣裙白底主图点击率高于场景图。", "keyword-vector");
        AnalysisInsight vectorOnly = seedInsight(
                "模特半身构图能提升女装主图点击表现。", "vector-only");
        String firstPointId = upsertFixture(keywordAndVector, new float[] {1.0f, 0.0f, 0.0f});
        upsertFixture(vectorOnly, new float[] {0.9f, 0.1f, 0.0f});
        long countBefore = adminClient.countAsync(COLLECTION, Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS);
        Points.RetrievedPoint pointBefore = retrieve(firstPointId);

        MemoryContext context = memoryService.prepareContext(new MemoryContextRequest(
                "conversation-1",
                1,
                "trace-1",
                "帮我处理 SKU123 的连衣裙主图，优先提升点击率",
                List.of(),
                List.of(),
                Map.of(),
                2000));

        assertThat(context.section(MemoryContextBuilder.ANALYSIS_INSIGHTS).items())
                .extracting(MemoryItem::id)
                .contains(String.valueOf(keywordAndVector.getId()), String.valueOf(vectorOnly.getId()));
        assertThat(context.recallTrace().toString()).contains("vector_candidates", "keyword_candidates");
        assertThat(insightMapper.selectById(keywordAndVector.getId()).getAccessCount()).isZero();
        assertThat(insightMapper.selectById(vectorOnly.getId()).getAccessCount()).isZero();
        assertThat(adminClient.countAsync(COLLECTION, Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS))
                .isEqualTo(countBefore);
        assertThat(retrieve(firstPointId)).isEqualTo(pointBefore);
    }

    private AnalysisInsight seedInsight(String text, String source) {
        AnalysisInsight insight = new AnalysisInsight();
        insight.setText(text);
        insight.setCategory("连衣裙");
        insight.setSource(source);
        insight.setConfidence(0.9);
        insight.setRelatedSku("SKU123");
        insight.setContentHash(contentHash(text, source));
        insight.setImportance(0.8);
        insight.setStatus(AnalysisInsightStatus.ACTIVE);
        insight.setAccessCount(0);
        insight.setDecayScore(1.0);
        insight.setCreatedAt(Instant.parse("2026-06-28T00:00:00Z"));
        insight.setUpdatedAt(Instant.parse("2026-06-28T00:00:00Z"));
        insightMapper.insert(insight);
        return insight;
    }

    private static String upsertFixture(AnalysisInsight insight, float[] vector) throws Exception {
        String pointId = UUID.randomUUID().toString();
        Points.PointStruct point = Points.PointStruct.newBuilder()
                .setId(PointIdFactory.id(UUID.fromString(pointId)))
                .setVectors(Points.Vectors.newBuilder().setVector(VectorFactory.vector(vector)).build())
                .putPayload("insight_id", ValueFactory.value(String.valueOf(insight.getId())))
                .putPayload("text", ValueFactory.value(insight.getText()))
                .putPayload("source", ValueFactory.value(insight.getSource()))
                .putPayload("category", ValueFactory.value(insight.getCategory()))
                .putPayload("related_sku", ValueFactory.value(insight.getRelatedSku()))
                .putPayload("confidence", ValueFactory.value(insight.getConfidence()))
                .putPayload("importance", ValueFactory.value(insight.getImportance()))
                .putPayload("decay_score", ValueFactory.value(insight.getDecayScore()))
                .putPayload("created_at", ValueFactory.value(insight.getCreatedAt().toString()))
                .build();
        adminClient.upsertAsync(COLLECTION, List.of(point), Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS);
        return pointId;
    }

    private static Points.RetrievedPoint retrieve(String pointId) throws Exception {
        return adminClient.retrieveAsync(
                        COLLECTION,
                        List.of(PointIdFactory.id(UUID.fromString(pointId))),
                        Points.WithPayloadSelector.newBuilder().setEnable(true).build(),
                        Points.WithVectorsSelector.newBuilder().setEnable(true).build(),
                        null,
                        Duration.ofSeconds(10))
                .get(10, TimeUnit.SECONDS)
                .getFirst();
    }

    private static float[] embedding(String text) {
        return new float[] {1.0f, 0.0f, 0.0f};
    }

    private static String contentHash(String text, String source) {
        String value = Integer.toHexString(text.hashCode()) + Integer.toHexString(source.hashCode());
        return (value + "00000000000000000000000000000000").substring(0, 32);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = AiAutoConfiguration.class)
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
        VectorSearch vectorSearch() throws Exception {
            VectorProperties properties = new VectorProperties();
            properties.getQdrant().setHost(QDRANT.getHost());
            properties.getQdrant().setGrpcPort(QDRANT.getMappedPort(6334));
            properties.getQdrant().setTimeout(Duration.ofSeconds(10));
            adminClient = new QdrantClient(QdrantGrpcClient.newBuilder(
                    properties.getQdrant().getHost(),
                    properties.getQdrant().getGrpcPort(),
                    false).build());
            Collections.VectorParams params = Collections.VectorParams.newBuilder()
                    .setSize(3)
                    .setDistance(Collections.Distance.Cosine)
                    .build();
            // 只有测试管理夹具可以建集合；应用注入的仍是只读 VectorSearch。
            adminClient.createCollectionAsync(COLLECTION, params, Duration.ofSeconds(10))
                    .get(10, TimeUnit.SECONDS);
            return new QdrantVectorSearch(properties, new NoopVectorMetrics());
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
