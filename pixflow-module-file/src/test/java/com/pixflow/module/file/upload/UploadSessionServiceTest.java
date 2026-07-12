package com.pixflow.module.file.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.DefaultCacheNamespace;
import com.pixflow.infra.cache.lock.LockTemplate;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StoredObjectMetadata;
import com.pixflow.module.file.config.FileProperties;
import com.pixflow.module.file.ingest.ExtractionPublisher;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.pkg.AssetPackageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class UploadSessionServiceTest {
    private InMemoryUploadSessionStore store;
    private MemoryObjectStorage storage;
    private UploadSessionService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryUploadSessionStore();
        storage = new MemoryObjectStorage();
        FileProperties properties = new FileProperties();
        properties.getUpload().setChunkSize(DataSize.ofBytes(3));
        AssetPackageMapper packageMapper = mock(AssetPackageMapper.class);
        when(packageMapper.selectOne(any())).thenReturn(null);
        service = new UploadSessionService(
                store,
                directLock(),
                new DefaultCacheNamespace("test", Duration.ofHours(1)),
                storage,
                packageMapper,
                mock(AssetPackageService.class),
                mock(ExtractionPublisher.class),
                properties,
                Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void partialUploadResumesWithOriginalShapeAndUploadedIndexes() {
        byte[] file = "abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        InitUploadResponse initial = service.init(new InitUploadRequest("demo.zip", file.length, sha256(file), 3));

        PutChunkResponse accepted = service.putChunk(initial.uploadId(), 0, 3, sha256("abc".getBytes()),
                new ByteArrayInputStream("abc".getBytes()));
        InitUploadResponse resumed = service.init(new InitUploadRequest("demo.zip", file.length, sha256(file), 3));

        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(resumed.mode()).isEqualTo("RESUME");
        assertThat(resumed.uploadId()).isEqualTo(initial.uploadId());
        assertThat(resumed.chunkSize()).isEqualTo(3);
        assertThat(resumed.expectedChunks()).isEqualTo(2);
        assertThat(resumed.uploadedChunks()).containsExactly(0);
    }

    @Test
    void duplicateChunkIsIdempotentButHashConflictFails() {
        InitUploadResponse initial = initSixBytes();
        byte[] chunk = "abc".getBytes();
        service.putChunk(initial.uploadId(), 0, 3, sha256(chunk), new ByteArrayInputStream(chunk));

        PutChunkResponse duplicate = service.putChunk(initial.uploadId(), 0, 3, sha256(chunk), new ByteArrayInputStream(chunk));

        assertThat(duplicate.status()).isEqualTo("ALREADY_EXISTS");
        assertThatThrownBy(() -> service.putChunk(initial.uploadId(), 0, 3,
                sha256("xyz".getBytes()), new ByteArrayInputStream("xyz".getBytes())))
                .isInstanceOf(PixFlowException.class)
                .hasMessageContaining("chunk hash mismatch");
    }

    @Test
    void missingObjectInvalidatesMetadataAndAllowsRetransmission() {
        InitUploadResponse initial = initSixBytes();
        byte[] chunk = "abc".getBytes();
        service.putChunk(initial.uploadId(), 0, 3, sha256(chunk), new ByteArrayInputStream(chunk));
        storage.deleteByPrefix(BucketType.TMP, "uploads/" + initial.uploadId() + "/");

        PutChunkResponse retried = service.putChunk(initial.uploadId(), 0, 3, sha256(chunk), new ByteArrayInputStream(chunk));

        assertThat(retried.status()).isEqualTo("ACCEPTED");
        assertThat(retried.uploadedChunks()).containsExactly(0);
    }

    @Test
    void incompleteCompleteIsRejectedAndSessionReturnsToUploading() {
        InitUploadResponse initial = initSixBytes();

        assertThatThrownBy(() -> service.complete(initial.uploadId(), new CompleteUploadRequest(null)))
                .isInstanceOf(PixFlowException.class)
                .hasMessageContaining("incomplete chunks");
        assertThat(store.findByUploadId(initial.uploadId()).orElseThrow().session().status())
                .isEqualTo("UPLOADING");
    }

    @Test
    void cancelDeletesTemporaryPrefixAndAllSessionState() {
        InitUploadResponse initial = initSixBytes();
        byte[] chunk = "abc".getBytes();
        service.putChunk(initial.uploadId(), 0, 3, sha256(chunk), new ByteArrayInputStream(chunk));

        CancelUploadResponse cancelled = service.cancel(initial.uploadId());

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(store.findByUploadId(initial.uploadId())).isEmpty();
        assertThat(storage.values).isEmpty();
    }

    private InitUploadResponse initSixBytes() {
        byte[] file = "abcdef".getBytes();
        return service.init(new InitUploadRequest("demo.zip", file.length, sha256(file), 3));
    }

    private static LockTemplate directLock() {
        return new LockTemplate() {
            @Override public <T> T runWithLock(CacheKey key, Duration waitTime, Supplier<T> action) { return action.get(); }
            @Override public void runWithLock(CacheKey key, Duration waitTime, Runnable action) { action.run(); }
            @Override public boolean tryRunWithLock(CacheKey key, Duration waitTime, java.util.function.Consumer<com.pixflow.infra.cache.lock.LockGuard> action) { action.accept(() -> true); return true; }
        };
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class MemoryObjectStorage implements ObjectStorage {
        private final Map<ObjectLocation, byte[]> values = new ConcurrentHashMap<>();

        @Override
        public ObjectRef put(ObjectLocation loc, InputStream data, long size, String contentType) {
            try {
                byte[] bytes = data.readAllBytes();
                values.put(loc, bytes);
                return new ObjectRef(loc.bucket(), loc.key(), bytes.length, "etag");
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override public InputStream getStream(ObjectLocation loc) { return new ByteArrayInputStream(values.get(loc)); }
        @Override public byte[] getBytes(ObjectLocation loc) { return values.get(loc); }
        @Override public boolean exists(ObjectLocation loc) { return values.containsKey(loc); }
        @Override public StoredObjectMetadata stat(ObjectLocation loc) {
            return new StoredObjectMetadata(values.get(loc).length, "application/octet-stream", "etag", Instant.now());
        }
        @Override public void delete(ObjectLocation loc) { values.remove(loc); }
        @Override public void deleteByPrefix(BucketType bucket, String prefix) {
            values.keySet().removeIf(location -> location.bucket() == bucket && location.key().startsWith(prefix));
        }
        @Override public URL presignGet(ObjectLocation loc, Duration ttl) { throw new UnsupportedOperationException(); }
        @Override public URL presignPut(ObjectLocation loc, Duration ttl) { throw new UnsupportedOperationException(); }
    }
}
