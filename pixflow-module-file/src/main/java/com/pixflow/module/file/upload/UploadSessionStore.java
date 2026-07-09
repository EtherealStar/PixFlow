package com.pixflow.module.file.upload;

import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.store.CacheStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class UploadSessionStore {
    private final CacheStore cacheStore;
    private final CacheNamespace namespace;
    private final Duration ttl;

    public UploadSessionStore(CacheStore cacheStore, CacheNamespace namespace, Duration ttl) {
        this.cacheStore = cacheStore;
        this.namespace = namespace;
        this.ttl = ttl;
    }

    public Optional<UploadSession> find(String uploadId) {
        return cacheStore.get(sessionKey(uploadId), UploadSession.class);
    }

    public void save(UploadSession session) {
        cacheStore.put(sessionKey(session.uploadId()), session, ttl);
        if ("UPLOADING".equals(session.status())) {
            cacheStore.put(fileHashKey(session.fileHash()), session.uploadId(), ttl);
        }
    }

    public Optional<String> findActiveUploadId(String fileHash) {
        return cacheStore.get(fileHashKey(fileHash), String.class);
    }

    public void saveChunk(String uploadId, ChunkMetadata metadata) {
        cacheStore.put(chunkKey(uploadId, metadata.index()), metadata, ttl);
    }

    public Optional<ChunkMetadata> findChunk(String uploadId, int index) {
        return cacheStore.get(chunkKey(uploadId, index), ChunkMetadata.class);
    }

    public List<Integer> uploadedChunks(UploadSession session) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < session.expectedChunks(); i++) {
            if (cacheStore.exists(chunkKey(session.uploadId(), i))) {
                out.add(i);
            }
        }
        return out;
    }

    public void deleteChunks(String uploadId, int expectedChunks) {
        for (int i = 0; i < expectedChunks; i++) {
            cacheStore.delete(chunkKey(uploadId, i));
        }
    }

    public void deleteSession(UploadSession session) {
        deleteChunks(session.uploadId(), session.expectedChunks());
        cacheStore.delete(fileHashKey(session.fileHash()));
        cacheStore.delete(sessionKey(session.uploadId()));
    }

    public void deleteActiveUpload(UploadSession session) {
        cacheStore.delete(fileHashKey(session.fileHash()));
    }

    public void delete(String uploadId, int expectedChunks) {
        deleteChunks(uploadId, expectedChunks);
        cacheStore.delete(sessionKey(uploadId));
    }

    private CacheKey sessionKey(String uploadId) {
        return namespace.withDefaultTtl(ttl).key("upload", "session", uploadId);
    }

    private CacheKey chunkKey(String uploadId, int index) {
        return namespace.withDefaultTtl(ttl).key("upload", "chunk", uploadId, String.valueOf(index));
    }

    private CacheKey fileHashKey(String fileHash) {
        return namespace.withDefaultTtl(ttl).key("upload", "filehash", fileHash);
    }
}
