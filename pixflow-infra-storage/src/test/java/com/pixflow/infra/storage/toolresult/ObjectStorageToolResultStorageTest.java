package com.pixflow.infra.storage.toolresult;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StoredObjectMetadata;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectStorageToolResultStorageTest {

    @Test
    void writeUsesStableKeyForSameToolCallAndContent() {
        InMemoryObjectStorage objectStorage = new InMemoryObjectStorage();
        ObjectStorageToolResultStorage storage = new ObjectStorageToolResultStorage(objectStorage);

        StoredToolResultReference first = storage.write("call-1", "abcdef", 3);
        StoredToolResultReference second = storage.write("call-1", "abcdef", 3);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.key()).isEqualTo(first.key());
        assertThat(second.preview()).isEqualTo("abc");
        assertThat(storage.read(first).content()).isEqualTo("abcdef");
    }

    @Test
    void readReturnsMissingReferenceWhenObjectDoesNotExist() {
        InMemoryObjectStorage objectStorage = new InMemoryObjectStorage();
        ObjectStorageToolResultStorage storage = new ObjectStorageToolResultStorage(objectStorage);
        StoredToolResultReference ref = storage.write("call-1", "abcdef", 3);
        objectStorage.objects.clear();

        StoredToolResultContent content = storage.read(ref);

        assertThat(content.content()).isEqualTo("abc");
        assertThat(content.reference().missing()).isTrue();
    }

    private static final class InMemoryObjectStorage implements ObjectStorage {
        private final Map<ObjectLocation, byte[]> objects = new LinkedHashMap<>();

        @Override
        public ObjectRef put(ObjectLocation loc, InputStream data, long size, String contentType) {
            try {
                byte[] bytes = data.readAllBytes();
                objects.put(loc, bytes);
                return new ObjectRef(loc.bucket(), loc.key(), bytes.length, "etag");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public InputStream getStream(ObjectLocation loc) {
            return new ByteArrayInputStream(objects.get(loc));
        }

        @Override
        public byte[] getBytes(ObjectLocation loc) {
            return objects.get(loc);
        }

        @Override
        public boolean exists(ObjectLocation loc) {
            return objects.containsKey(loc);
        }

        @Override
        public StoredObjectMetadata stat(ObjectLocation loc) {
            return new StoredObjectMetadata(objects.get(loc).length, "text/plain", "etag", Instant.now());
        }

        @Override
        public void delete(ObjectLocation loc) {
            objects.remove(loc);
        }

        @Override
        public void deleteByPrefix(BucketType bucket, String prefix) {
            objects.keySet().removeIf(location -> location.bucket() == bucket && location.key().startsWith(prefix));
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
