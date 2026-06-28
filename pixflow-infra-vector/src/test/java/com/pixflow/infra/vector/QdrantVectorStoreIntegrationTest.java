package com.pixflow.infra.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.vector.observability.NoopVectorMetrics;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class QdrantVectorStoreIntegrationTest {
    @Container
    private static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.18.2")
            .withExposedPorts(6334);

    private static QdrantClient client;
    private static QdrantVectorStore store;

    @BeforeAll
    static void setUp() {
        VectorProperties properties = new VectorProperties();
        properties.setHost(QDRANT.getHost());
        properties.setPort(QDRANT.getMappedPort(6334));
        properties.setTimeout(Duration.ofSeconds(10));
        client = new QdrantClient(QdrantGrpcClient.newBuilder(properties.getHost(), properties.getPort(), false).build());
        store = new QdrantVectorStore(client, properties, new NoopVectorMetrics());
        store.healthCheck();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void ensureCollectionIsIdempotentAndRejectsMismatchedConfig() {
        String collection = collectionName();

        store.ensureCollection(collection, 3, Distance.COSINE);
        store.ensureCollection(collection, 3, Distance.COSINE);

        assertThat(store.collectionExists(collection)).isTrue();
        assertThatThrownBy(() -> store.ensureCollection(collection, 4, Distance.COSINE))
                .isInstanceOf(VectorException.class)
                .extracting("retryable")
                .isEqualTo(false);
    }

    @Test
    void upsertGetSearchAndDeleteRoundTrip() {
        String collection = collectionName();
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        String id3 = UUID.randomUUID().toString();
        store.ensureCollection(collection, 3, Distance.COSINE);

        store.upsert(collection, List.of(
                new VectorPoint(id1, new float[] {1.0f, 0.0f, 0.0f}, Map.of("type", "a", "score", 0.9)),
                new VectorPoint(id2, new float[] {0.9f, 0.1f, 0.0f}, Map.of("type", "a", "score", 0.8)),
                new VectorPoint(id3, new float[] {0.0f, 1.0f, 0.0f}, Map.of("type", "b", "score", 0.3))));
        store.upsert(collection, List.of(new VectorPoint(id1, new float[] {1.0f, 0.0f, 0.0f}, Map.of("type", "updated", "score", 1.0))));

        assertThat(store.get(collection, id1)).hasValueSatisfying(point ->
                assertThat(point.payload()).containsEntry("type", "updated"));

        List<ScoredPoint> filtered = store.search(
                collection,
                new float[] {1.0f, 0.0f, 0.0f},
                3,
                0.1f,
                VectorFilter.must(VectorFilter.match("type", "a")));

        assertThat(filtered).extracting(ScoredPoint::id).containsExactly(id2);

        store.delete(collection, List.of(id2));
        assertThat(store.get(collection, id2)).isEmpty();

        store.deleteByFilter(collection, VectorFilter.must(VectorFilter.range("score", 0.9, null)));
        assertThat(store.get(collection, id1)).isEmpty();
        assertThat(store.get(collection, id3)).isPresent();
    }

    private static String collectionName() {
        return "test_" + UUID.randomUUID().toString().replace("-", "");
    }
}
