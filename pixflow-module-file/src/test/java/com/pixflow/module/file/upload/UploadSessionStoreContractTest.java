package com.pixflow.module.file.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UploadSessionStoreContractTest {
    private static final String HASH = "a".repeat(64);
    private InMemoryUploadSessionStore store;
    private UploadSession session;

    @BeforeEach
    void setUp() {
        store = new InMemoryUploadSessionStore();
        Instant now = Instant.parse("2026-07-11T00:00:00Z");
        session = new UploadSession("upload-1", "demo.zip", 9, HASH, 3, 3,
                "UPLOADING", null, now, now, now.plusSeconds(3600));
        store.create(session);
    }

    @Test
    void createCanBeResolvedByFileHashAndUploadId() {
        assertThat(store.findByFileHash(HASH)).isPresent();
        assertThat(store.findByUploadId("upload-1")).isPresent();
    }

    @Test
    void snapshotIsSortedAndImmutable() {
        store.recordChunk("upload-1", chunk(2, "c"));
        store.recordChunk("upload-1", chunk(0, "a"));

        UploadSnapshot snapshot = store.findByUploadId("upload-1").orElseThrow();

        assertThat(snapshot.chunks().keySet()).containsExactly(0, 2);
        assertThatThrownByMutation(snapshot);
    }

    @Test
    void recordChunkDistinguishesCreateIdempotencyAndConflict() {
        assertThat(store.recordChunk("upload-1", chunk(0, "a"))).isEqualTo(ChunkWriteResult.CREATED);
        assertThat(store.recordChunk("upload-1", chunk(0, "a"))).isEqualTo(ChunkWriteResult.ALREADY_EXISTS);
        assertThat(store.recordChunk("upload-1", chunk(0, "b"))).isEqualTo(ChunkWriteResult.HASH_CONFLICT);
    }

    @Test
    void deleteRemovesSessionActiveIndexAndChunks() {
        store.recordChunk("upload-1", chunk(0, "a"));
        store.delete(store.findByUploadId("upload-1").orElseThrow());

        assertThat(store.findByUploadId("upload-1")).isEmpty();
        assertThat(store.findByFileHash(HASH)).isEmpty();
    }

    private static ChunkMetadata chunk(int index, String seed) {
        return new ChunkMetadata(index, seed.repeat(64), 3, "uploads/upload-1/chunks/" + index);
    }

    private static void assertThatThrownByMutation(UploadSnapshot snapshot) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.chunks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
