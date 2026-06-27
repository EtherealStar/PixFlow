package com.pixflow.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        public URL presignGet(ObjectLocation loc, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public URL presignPut(ObjectLocation loc, Duration ttl) {
            throw new UnsupportedOperationException();
        }
    }
}
