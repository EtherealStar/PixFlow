package com.pixflow.module.file.upload;

import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.state.ExpiringHashStore;
import com.pixflow.infra.cache.state.ExpiringStateStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import com.pixflow.module.file.api.activity.UploadActivitySnapshot;

public final class RedisUploadSessionStore implements UploadSessionStore {
    private final ExpiringStateStore stateStore;

    private final ExpiringHashStore hashStore;

    private final CacheNamespace namespace;

    private final Duration ttl;

    private final Clock clock;

    public RedisUploadSessionStore(
            ExpiringStateStore stateStore,
            ExpiringHashStore hashStore,
            CacheNamespace namespace,
            Duration ttl,
            Clock clock) {
        this.stateStore = stateStore;
        this.hashStore = hashStore;
        this.namespace = namespace;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public Optional<UploadSnapshot> findByFileHash(String fileHash) {
        Optional<String> uploadId = stateStore.get(fileHashKey(fileHash), String.class);
        if (uploadId.isEmpty()) {
            return Optional.empty();
        }
        Optional<UploadSnapshot> snapshot = findByUploadId(uploadId.get());
        if (snapshot.isEmpty()) {
            stateStore.delete(fileHashKey(fileHash));
        }
        return snapshot;
    }

    @Override
    public Optional<UploadSnapshot> findByUploadId(String uploadId) {
        Map<String, String> fields = hashStore.entries(sessionKey(uploadId), String.class);
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        UploadSession session = decodeSession(uploadId, fields);
        Map<Integer, ChunkMetadata> chunks = new HashMap<>();
        hashStore.entries(chunksKey(uploadId), ChunkMetadata.class)
                .forEach((index, metadata) -> chunks.put(Integer.parseInt(index), metadata));
        return Optional.of(new UploadSnapshot(session, chunks));
    }

    @Override
    public List<UploadActivitySnapshot> listActivitySnapshots() {
        return hashStore.entries(indexKey(), String.class).keySet().stream()
                .sorted()
                .map(this::findActivity)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public void create(UploadSession session) {
        UploadSession renewed = session.withExpiry(clock.instant().plus(ttl));
        writeSession(renewed);
        stateStore.put(fileHashKey(renewed.fileHash()), renewed.uploadId(), ttl);
        index(renewed);
    }

    @Override
    public void touch(UploadSnapshot snapshot) {
        UploadSession renewed = snapshot.session().withExpiry(clock.instant().plus(ttl));
        writeSession(renewed);
        if ("UPLOADING".equals(renewed.status())) {
            stateStore.put(fileHashKey(renewed.fileHash()), renewed.uploadId(), ttl);
        } else {
            stateStore.expire(fileHashKey(renewed.fileHash()), ttl);
        }
        if (!snapshot.chunks().isEmpty()) {
            hashStore.expire(chunksKey(renewed.uploadId()), ttl);
        }
        index(renewed);
    }

    @Override
    public void save(UploadSession session) {
        writeSession(session.withExpiry(clock.instant().plus(ttl)));
        if ("UPLOADING".equals(session.status())) {
            stateStore.put(fileHashKey(session.fileHash()), session.uploadId(), ttl);
        } else {
            stateStore.expire(fileHashKey(session.fileHash()), ttl);
        }
        hashStore.expire(chunksKey(session.uploadId()), ttl);
        index(session.withExpiry(clock.instant().plus(ttl)));
    }

    @Override
    public ChunkWriteResult recordChunk(String uploadId, ChunkMetadata metadata) {
        Optional<ChunkMetadata> existing =
                hashStore.get(chunksKey(uploadId), field(metadata.index()), ChunkMetadata.class);
        if (existing.isPresent()) {
            if (existing.get().chunkHash().equalsIgnoreCase(metadata.chunkHash())) {
                findByUploadId(uploadId).ifPresent(this::touch);
                return ChunkWriteResult.ALREADY_EXISTS;
            }
            return ChunkWriteResult.HASH_CONFLICT;
        }
        hashStore.put(chunksKey(uploadId), field(metadata.index()), metadata, ttl);
        findByUploadId(uploadId).ifPresent(this::touch);
        return ChunkWriteResult.CREATED;
    }

    @Override
    public void removeChunk(String uploadId, int index) {
        hashStore.deleteField(chunksKey(uploadId), field(index));
    }

    @Override
    public void clearChunksAndActive(UploadSession session) {
        hashStore.delete(chunksKey(session.uploadId()));
        stateStore.delete(fileHashKey(session.fileHash()));
        hashStore.deleteField(indexKey(), session.uploadId());
    }

    @Override
    public void delete(UploadSnapshot snapshot) {
        UploadSession session = snapshot.session();
        hashStore.delete(chunksKey(session.uploadId()));
        stateStore.delete(fileHashKey(session.fileHash()));
        hashStore.delete(sessionKey(session.uploadId()));
        hashStore.deleteField(indexKey(), session.uploadId());
    }

    @Override
    public List<String> findExpiredUploadIds(Instant now, int limit) {
        return hashStore.entries(indexKey(), String.class).entrySet().stream()
                .filter(entry -> {
                    try {
                        return !Instant.parse(entry.getValue()).isAfter(now);
                    } catch (RuntimeException malformed) {
                        return true;
                    }
                })
                .map(Map.Entry::getKey)
                .sorted()
                .limit(limit)
                .toList();
    }

    @Override
    public void forgetUploadId(String uploadId) {
        hashStore.deleteField(indexKey(), uploadId);
    }

    private void writeSession(UploadSession session) {
        CacheKey key = sessionKey(session.uploadId());
        put(key, "filename", session.filename());
        put(key, "size", Long.toString(session.size()));
        put(key, "fileHash", session.fileHash());
        put(key, "chunkSize", Long.toString(session.chunkSize()));
        put(key, "expectedChunks", Integer.toString(session.expectedChunks()));
        put(key, "status", session.status());
        if (session.packageId() == null) {
            hashStore.deleteField(key, "packageId");
        } else {
            put(key, "packageId", Long.toString(session.packageId()));
        }
        put(key, "createdAt", session.createdAt().toString());
        put(key, "updatedAt", session.updatedAt().toString());
        put(key, "expiresAt", session.expiresAt().toString());
    }

    private void put(CacheKey key, String field, String value) {
        hashStore.put(key, field, value, ttl);
    }

    private UploadSession decodeSession(String uploadId, Map<String, String> fields) {
        return new UploadSession(
                uploadId,
                required(fields, "filename"),
                Long.parseLong(required(fields, "size")),
                required(fields, "fileHash"),
                Long.parseLong(required(fields, "chunkSize")),
                Integer.parseInt(required(fields, "expectedChunks")),
                required(fields, "status"),
                fields.containsKey("packageId") ? Long.parseLong(fields.get("packageId")) : null,
                Instant.parse(required(fields, "createdAt")),
                Instant.parse(required(fields, "updatedAt")),
                Instant.parse(required(fields, "expiresAt")));
    }

    private static String required(Map<String, String> fields, String field) {
        String value = fields.get(field);
        if (value == null) {
            throw new IllegalStateException("上传会话缺少字段: " + field);
        }
        return value;
    }

    private CacheKey sessionKey(String uploadId) {
        return namespace.withDefaultTtl(ttl).key("upload", "session", uploadId);
    }

    private CacheKey chunksKey(String uploadId) {
        return namespace.withDefaultTtl(ttl).key("upload", "chunks", uploadId);
    }

    private CacheKey fileHashKey(String fileHash) {
        return namespace.withDefaultTtl(ttl).key("upload", "filehash", fileHash);
    }

    private CacheKey indexKey() {
        return namespace.withDefaultTtl(ttl.multipliedBy(3)).key("upload", "index");
    }

    private void index(UploadSession session) {
        hashStore.put(indexKey(), session.uploadId(), session.expiresAt().toString(), ttl.multipliedBy(3));
    }

    private static String field(int index) {
        return Integer.toString(index);
    }
}
