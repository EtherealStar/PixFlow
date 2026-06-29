package com.pixflow.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class MinioObjectStorageIntegrationTest {
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2024-09-13T20-26-02Z"))
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data");

    private static ObjectStorage storage;

    @BeforeAll
    static void startMinio() {
        MINIO.start();

        StorageProperties properties = new StorageProperties();
        properties.setEndpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        properties.setAccessKey(ACCESS_KEY);
        properties.setSecretKey(SECRET_KEY);
        properties.setUploadPartSize(DataSize.ofMegabytes(5));
        properties.getBuckets().setPackages("it-packages");
        properties.getBuckets().setResults("it-results");
        properties.getBuckets().setGenerated("it-generated");
        properties.getBuckets().setToolResults("it-tool-results");
        properties.getBuckets().setTmp("it-tmp");

        var client = io.minio.MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(ACCESS_KEY, SECRET_KEY)
                .build();
        var resolver = new DefaultStorageBucketResolver(properties);
        new StorageInitializer(client, resolver, properties).run(null);
        storage = new MinioObjectStorage(client, resolver, properties);
    }

    @AfterAll
    static void stopMinio() {
        if (MINIO.isRunning()) {
            MINIO.stop();
        }
    }

    @Test
    void putGetStatPresignAndDeleteRoundTrip() throws Exception {
        ObjectLocation location = StorageKeys.packageDoc(1, "copy.txt");
        byte[] payload = "hello storage".getBytes();

        ObjectRef ref = storage.put(location, new ByteArrayInputStream(payload), payload.length, "text/plain");

        assertThat(ref.key()).isEqualTo("1/doc/copy.txt");
        assertThat(storage.exists(location)).isTrue();
        assertThat(storage.getBytes(location)).isEqualTo(payload);
        assertThat(storage.stat(location).contentType()).startsWith("text/plain");

        URI uri = storage.presignGet(location, Duration.ofMinutes(1)).toURI();
        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(payload);

        storage.delete(location);
        assertThat(storage.exists(location)).isFalse();
    }

    @Test
    void deleteByPrefixOnlyRemovesMatchingObjects() {
        storage.put(ObjectLocation.of(BucketType.TMP, "task-1/a.txt"), new ByteArrayInputStream("a".getBytes()), 1, "text/plain");
        storage.put(ObjectLocation.of(BucketType.TMP, "task-1/b.txt"), new ByteArrayInputStream("b".getBytes()), 1, "text/plain");
        storage.put(ObjectLocation.of(BucketType.TMP, "task-2/c.txt"), new ByteArrayInputStream("c".getBytes()), 1, "text/plain");

        storage.deleteByPrefix(BucketType.TMP, "task-1/");

        assertThat(storage.exists(ObjectLocation.of(BucketType.TMP, "task-1/a.txt"))).isFalse();
        assertThat(storage.exists(ObjectLocation.of(BucketType.TMP, "task-1/b.txt"))).isFalse();
        assertThat(storage.exists(ObjectLocation.of(BucketType.TMP, "task-2/c.txt"))).isTrue();
    }
}
