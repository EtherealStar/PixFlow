package com.pixflow.module.file.upload;

import com.pixflow.module.file.api.activity.UploadActivitySnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryUploadSessionStore implements UploadSessionStore {
    private final Map<String, UploadSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> active = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, ChunkMetadata>> chunks = new ConcurrentHashMap<>();

    @Override
    public Optional<UploadSnapshot> findByFileHash(String fileHash) {
        return Optional.ofNullable(active.get(fileHash)).flatMap(this::findByUploadId);
    }

    @Override
    public Optional<UploadSnapshot> findByUploadId(String uploadId) {
        return Optional.ofNullable(sessions.get(uploadId))
                .map(session -> new UploadSnapshot(session, chunks.getOrDefault(uploadId, Map.of())));
    }

    @Override
    public List<UploadActivitySnapshot> listActivitySnapshots() {
        return sessions.keySet().stream()
                .sorted()
                .map(this::findActivity)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public void create(UploadSession session) {
        sessions.put(session.uploadId(), session);
        active.put(session.fileHash(), session.uploadId());
    }

    @Override
    public void touch(UploadSnapshot snapshot) {
        sessions.put(snapshot.session().uploadId(), snapshot.session());
    }

    @Override
    public void save(UploadSession session) {
        sessions.put(session.uploadId(), session);
        if ("UPLOADING".equals(session.status())) active.put(session.fileHash(), session.uploadId());
    }

    @Override
    public ChunkWriteResult recordChunk(String uploadId, ChunkMetadata metadata) {
        Map<Integer, ChunkMetadata> values = chunks.computeIfAbsent(uploadId, ignored -> new ConcurrentHashMap<>());
        ChunkMetadata existing = values.putIfAbsent(metadata.index(), metadata);
        if (existing == null) return ChunkWriteResult.CREATED;
        return existing.chunkHash().equalsIgnoreCase(metadata.chunkHash())
                ? ChunkWriteResult.ALREADY_EXISTS : ChunkWriteResult.HASH_CONFLICT;
    }

    @Override
    public void removeChunk(String uploadId, int index) {
        Map<Integer, ChunkMetadata> values = chunks.get(uploadId);
        if (values != null) values.remove(index);
    }

    @Override
    public void clearChunksAndActive(UploadSession session) {
        chunks.remove(session.uploadId());
        active.remove(session.fileHash());
    }

    @Override
    public void delete(UploadSnapshot snapshot) {
        clearChunksAndActive(snapshot.session());
        sessions.remove(snapshot.session().uploadId());
    }
}
