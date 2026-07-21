package com.pixflow.module.rubrics.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.image.config.ImageProperties;
import com.pixflow.infra.image.impl.DefaultImageCodec;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.DefaultStorageBucketResolver;
import com.pixflow.infra.storage.MinioObjectStorage;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageInitializer;
import com.pixflow.infra.storage.StorageProperties;
import com.pixflow.module.rubrics.subject.ImageResultSubject;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = false)
class ImageEvidencePackBuilderMinioIntegrationTest {
    private static final String ACCESS_KEY = "minioadmin";

    private static final String SECRET_KEY = "minioadmin";

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
            "minio/minio:RELEASE.2024-09-13T20-26-02Z"))
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data");

    private static ObjectStorage storage;

    @BeforeAll
    static void startMinio() {
        StorageProperties properties = new StorageProperties();
        properties.setEndpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        properties.setAccessKey(ACCESS_KEY);
        properties.setSecretKey(SECRET_KEY);
        properties.setUploadPartSize(DataSize.ofMegabytes(5));
        properties.getBuckets().setGenerated("rubrics-generated");
        properties.getBuckets().setPackages("rubrics-packages");
        properties.getBuckets().setResults("rubrics-results");
        properties.getBuckets().setToolResults("rubrics-tool-results");
        properties.getBuckets().setTmp("rubrics-tmp");
        var client = io.minio.MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(ACCESS_KEY, SECRET_KEY)
                .build();
        var resolver = new DefaultStorageBucketResolver(properties);
        new StorageInitializer(client, resolver, properties).run(null);
        storage = new MinioObjectStorage(client, resolver, properties);
    }

    @Test
    void readsStableImageBytesFromMinio() throws Exception {
        byte[] bytes = png(2, 2, Color.BLUE);
        ObjectLocation location = location("normal.png");
        put(location, bytes);

        EvidencePack pack = builder(reader(location, 91, EvidenceHashing.sha256(bytes)), codec()).build(
                subject(bytes.length));

        assertThat(pack.failure()).isNull();
        assertThat(pack.entries()).hasSize(2);
    }

    @Test
    void deletedObjectIsNonReplayable() throws Exception {
        byte[] bytes = png(2, 2, Color.GREEN);
        ObjectLocation location = location("deleted.png");
        put(location, bytes);
        PublishedAssetReader reader = reader(location, 91, EvidenceHashing.sha256(bytes));
        storage.delete(location);

        EvidencePack pack = builder(reader, codec()).build(subject(bytes.length));

        assertThat(pack.failure().kind()).isEqualTo(EvidenceFailureKind.NON_REPLAYABLE);
        assertThat(pack.failure().code()).isEqualTo("PUBLISHED_ASSET_UNAVAILABLE");
    }

    @Test
    void sameLengthReplacementIsInvalidIdentity() throws Exception {
        byte[] original = png(2, 2, Color.RED);
        ObjectLocation location = location("replaced.png");
        put(location, original);
        PublishedAssetReader reader = reader(location, 91, EvidenceHashing.sha256(original));
        byte[] replacement = new byte[original.length];
        put(location, replacement);

        EvidencePack pack = builder(reader, codec()).build(subject(original.length));

        assertThat(pack.failure().kind()).isEqualTo(EvidenceFailureKind.INVALID_IDENTITY);
        assertThat(pack.failure().code()).isEqualTo("PUBLISHED_ASSET_CONTENT_MISMATCH");
    }

    @Test
    void corruptedObjectIsInvalidContent() {
        byte[] bytes = "not-an-image".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ObjectLocation location = location("corrupted.png");
        put(location, bytes);

        EvidencePack pack = builder(reader(location, 91, EvidenceHashing.sha256(bytes)), codec()).build(
                subject(bytes.length));

        assertThat(pack.failure().kind()).isEqualTo(EvidenceFailureKind.INVALID_CONTENT);
        assertThat(pack.failure().code()).isEqualTo("IMAGE_PROBE_FAILED");
    }

    @Test
    void oversizedImageIsRejectedByTheImageBudget() throws Exception {
        byte[] bytes = png(2, 2, Color.BLACK);
        ObjectLocation location = location("oversized.png");
        put(location, bytes);
        ImageProperties properties = new ImageProperties();
        properties.setMaxSourcePixels(1);

        EvidencePack pack = builder(
                reader(location, 91, EvidenceHashing.sha256(bytes)), new DefaultImageCodec(properties))
                .build(subject(bytes.length));

        assertThat(pack.failure().kind()).isEqualTo(EvidenceFailureKind.INVALID_CONTENT);
        assertThat(pack.failure().code()).isEqualTo("IMAGE_PIXEL_BUDGET_REJECTED");
    }

    private static ImageEvidencePackBuilder builder(
            PublishedAssetReader reader, DefaultImageCodec codec) {
        return new ImageEvidencePackBuilder(reader, codec, new ObjectMapper(), Clock.systemUTC());
    }

    private static DefaultImageCodec codec() {
        return new DefaultImageCodec(new ImageProperties());
    }

    private static PublishedAssetReader reader(
            ObjectLocation location, long imageId, String frozenContentHash) {
        return referenceKey -> {
            if (!storage.exists(location)) {
                return Optional.empty();
            }
            var metadata = storage.stat(location);
            PublishedAssetReader.ContentAccess access = new PublishedAssetReader.ContentAccess() {
                @Override
                public InputStream open() {
                    return storage.getStream(location);
                }

                @Override
                public java.net.URL presign(Duration ttl) {
                    return storage.presignGet(location, ttl);
                }
            };
            return Optional.of(new PublishedAssetReader.PublishedAssetContent(
                    imageId, metadata.contentType(), frozenContentHash, metadata.size(), access));
        };
    }

    private static ImageResultSubject subject(long bytesOut) {
        return new ImageResultSubject(
                "91", 42, "sku", "STANDARD", "image", null, null, "branch",
                91, "IMAGE:42:91", bytesOut, "provider", "model", "snapshot");
    }

    private static ObjectLocation location(String name) {
        return ObjectLocation.of(BucketType.GENERATED, "rubrics/" + name);
    }

    private static void put(ObjectLocation location, byte[] bytes) {
        storage.put(location, new ByteArrayInputStream(bytes), bytes.length, "image/png");
    }

    private static byte[] png(int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
