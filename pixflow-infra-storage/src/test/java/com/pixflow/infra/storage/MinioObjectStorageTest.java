package com.pixflow.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.minio.MinioClient;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class MinioObjectStorageTest {

    @Test
    void getBytesRejectsObjectsOverConfiguredLimit() {
        StorageProperties properties = new StorageProperties();
        properties.setMaxBytesReadSize(DataSize.ofBytes(3));
        MinioObjectStorage storage = new FakeObjectStorage(properties, "abcd".getBytes());

        assertThatThrownBy(() -> storage.getBytes(ObjectLocation.of(BucketType.TOOL_RESULTS, "x.txt")))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("maxBytesReadSize");
    }

    @Test
    void getBytesReadsSmallObject() {
        StorageProperties properties = new StorageProperties();
        properties.setMaxBytesReadSize(DataSize.ofBytes(10));
        MinioObjectStorage storage = new FakeObjectStorage(properties, "abc".getBytes());

        assertThat(storage.getBytes(ObjectLocation.of(BucketType.TOOL_RESULTS, "x.txt")))
                .isEqualTo("abc".getBytes());
    }

    @Test
    void copyMapsClientErrorsWithSourceAndTargetContext() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.copyObject(any())).thenThrow(new IOException("connection reset"));
        MinioObjectStorage storage = new MinioObjectStorage(
                client,
                bucket -> "bucket-" + bucket.name().toLowerCase(),
                new StorageProperties());
        ObjectLocation source = ObjectLocation.of(BucketType.TMP, "candidate.webp");
        ObjectLocation target = StorageKeys.generatedAsset(3, 9, "webp");

        assertThatThrownBy(() -> storage.copy(source, target))
                .isInstanceOfSatisfying(StorageException.class, exception -> {
                    assertThat(exception.operation()).isEqualTo("COPY");
                    assertThat(exception.bucket()).isEqualTo(BucketType.GENERATED);
                    assertThat(exception.key()).isEqualTo(target.key());
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.details())
                            .containsEntry("sourceBucket", "TMP")
                            .containsEntry("sourceKey", source.key())
                            .containsEntry("targetKey", target.key());
                });
    }

    @Test
    void copyRejectsNullLocations() {
        MinioObjectStorage storage = new MinioObjectStorage(null, bucket -> "unused", new StorageProperties());

        assertThatThrownBy(() -> storage.copy(null, ObjectLocation.of(BucketType.TMP, "target")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.copy(ObjectLocation.of(BucketType.TMP, "source"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static class FakeObjectStorage extends MinioObjectStorage {
        private final byte[] bytes;

        FakeObjectStorage(StorageProperties properties, byte[] bytes) {
            super(null, bucket -> "unused", properties);
            this.bytes = bytes;
        }

        @Override
        public InputStream getStream(ObjectLocation loc) {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public ObjectRef put(ObjectLocation loc, InputStream data, long size, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(ObjectLocation loc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredObjectMetadata stat(ObjectLocation loc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(ObjectLocation loc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteByPrefix(BucketType bucket, String prefix) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ObjectRef copy(ObjectLocation source, ObjectLocation target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public URL presignGet(ObjectLocation loc, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public URL presignPut(ObjectLocation loc, Duration ttl) {
            throw new UnsupportedOperationException();
        }
    }
}
