package com.pixflow.infra.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.vector.observability.NoopVectorMetrics;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class QdrantVectorSearchIntegrationTest {
    @Container
    private static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.18.2")
            .withExposedPorts(6334);

    private static QdrantClient adminClient;
    private static QdrantVectorSearch search;

    @BeforeAll
    static void setUp() {
        VectorProperties properties = new VectorProperties();
        properties.getQdrant().setHost(QDRANT.getHost());
        properties.getQdrant().setGrpcPort(QDRANT.getMappedPort(6334));
        properties.getQdrant().setTimeout(Duration.ofSeconds(10));
        adminClient = client(properties);
        search = new QdrantVectorSearch(client(properties), properties, new NoopVectorMetrics());
    }

    @AfterAll
    static void tearDown() {
        if (search != null) {
            search.close();
        }
        if (adminClient != null) {
            adminClient.close();
        }
    }

    @Test
    void readsFixturePreparedThroughTestOnlyAdminClient() throws Exception {
        String collection = collectionName();
        String manhattanCollection = collectionName();
        String firstId = UUID.randomUUID().toString();
        String secondId = UUID.randomUUID().toString();
        String thirdId = UUID.randomUUID().toString();
        try {
            assertThatThrownBy(() -> search.verifyCollection(collection, 3, Distance.COSINE))
                    .isInstanceOfSatisfying(VectorException.class,
                            exception -> assertThat(exception.failureKind())
                                    .isEqualTo(VectorException.FailureKind.COLLECTION_INVALID));
            createCollection(collection, 3, Collections.Distance.Cosine);
            createCollection(manhattanCollection, 3, Collections.Distance.Manhattan);
            upsert(collection, firstId, new float[] {1.0f, 0.0f, 0.0f}, "a");
            upsert(collection, secondId, new float[] {0.8f, 0.6f, 0.0f}, "b");
            upsert(collection, thirdId, new float[] {0.0f, 1.0f, 0.0f}, "c");
            long pointCountBeforeRead = adminClient.countAsync(collection, Duration.ofSeconds(10))
                    .get(10, TimeUnit.SECONDS);
            Points.RetrievedPoint firstPointBeforeRead = retrieve(collection, firstId);

            search.verifyCollection(collection, 3, Distance.COSINE);
            assertThatThrownBy(() -> search.verifyCollection(manhattanCollection, 3, Distance.EUCLID))
                    .isInstanceOf(VectorException.class);
            assertThatThrownBy(() -> search.verifyCollection(collection, 3, Distance.DOT))
                    .isInstanceOf(VectorException.class)
                    .extracting("retryable")
                    .isEqualTo(false);
            assertThat(search.search(
                    collection,
                    new float[] {1.0f, 0.0f, 0.0f},
                    10,
                    0.5f,
                    null))
                    .extracting(ScoredPoint::id)
                    .containsExactly(firstId, secondId);
            assertThat(search.search(
                    collection,
                    new float[] {1.0f, 0.0f, 0.0f},
                    1,
                    0.5f,
                    null))
                    .extracting(ScoredPoint::id)
                    .containsExactly(firstId);
            assertThat(search.search(
                    collection,
                    new float[] {1.0f, 0.0f, 0.0f},
                    1,
                    0.1f,
                    VectorFilter.must(VectorFilter.match("type", "a"))))
                    .extracting(ScoredPoint::id)
                    .containsExactly(firstId);
            assertThat(search.get(collection, firstId)).hasValueSatisfying(point -> {
                assertThat(point.vector()).containsExactly(1.0f, 0.0f, 0.0f);
                assertThat(point.payload()).containsEntry("type", "a");
                point.vector()[0] = 9.0f;
                assertThatThrownBy(() -> point.payload().put("type", "changed"))
                        .isInstanceOf(UnsupportedOperationException.class);
            });
            assertThat(search.get(collection, firstId)).hasValueSatisfying(point ->
                    assertThat(point.vector()).containsExactly(1.0f, 0.0f, 0.0f));
            assertThat(search.get(collection, UUID.randomUUID().toString())).isEmpty();
            assertThatThrownBy(() -> search.verifyCollection(collection, 4, Distance.COSINE))
                    .isInstanceOf(VectorException.class)
                    .extracting("retryable")
                    .isEqualTo(false);
            assertThat(adminClient.countAsync(collection, Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS))
                    .isEqualTo(pointCountBeforeRead);
            assertThat(retrieve(collection, firstId)).isEqualTo(firstPointBeforeRead);
        } finally {
            adminClient.deleteCollectionAsync(collection, Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS);
            adminClient.deleteCollectionAsync(manhattanCollection, Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS);
        }
    }

    private static QdrantClient client(VectorProperties properties) {
        return new QdrantClient(QdrantGrpcClient.newBuilder(
                properties.getQdrant().getHost(),
                properties.getQdrant().getGrpcPort(),
                false).build());
    }

    private static void createCollection(
            String collection,
            int dimension,
            Collections.Distance distance) throws Exception {
        Collections.VectorParams params = Collections.VectorParams.newBuilder()
                .setSize(dimension)
                .setDistance(distance)
                .build();
        adminClient.createCollectionAsync(collection, params, Duration.ofSeconds(10))
                .get(10, TimeUnit.SECONDS);
    }

    private static void upsert(String collection, String id, float[] vector, String type) throws Exception {
        Points.PointStruct point = Points.PointStruct.newBuilder()
                .setId(PointIdFactory.id(UUID.fromString(id)))
                .setVectors(Points.Vectors.newBuilder().setVector(VectorFactory.vector(vector)).build())
                .putPayload("type", ValueFactory.value(type))
                .build();
        adminClient.upsertAsync(collection, List.of(point), Duration.ofSeconds(10))
                .get(10, TimeUnit.SECONDS);
    }

    private static Points.RetrievedPoint retrieve(String collection, String id) throws Exception {
        return adminClient.retrieveAsync(
                        collection,
                        List.of(PointIdFactory.id(UUID.fromString(id))),
                        Points.WithPayloadSelector.newBuilder().setEnable(true).build(),
                        Points.WithVectorsSelector.newBuilder().setEnable(true).build(),
                        null,
                        Duration.ofSeconds(10))
                .get(10, TimeUnit.SECONDS)
                .getFirst();
    }

    private static String collectionName() {
        return "test_" + UUID.randomUUID().toString().replace("-", "");
    }
}
