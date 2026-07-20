package com.pixflow.module.file.upload;

import java.util.Optional;
import java.time.Instant;
import java.util.List;
import com.pixflow.module.file.api.activity.UploadActivitySnapshot;

/**
 * 上传会话的领域存储边界。调用方只处理快照和幂等结果，不感知 Redis key、Hash 或 TTL。
 */
public interface UploadSessionStore {
    Optional<UploadSnapshot> findByFileHash(String fileHash);

    Optional<UploadSnapshot> findByUploadId(String uploadId);

    List<UploadActivitySnapshot> listActivitySnapshots();

    default Optional<UploadActivitySnapshot> findActivity(String uploadId) {
        return findByUploadId(uploadId).map(snapshot -> {
            UploadSession session = snapshot.session();
            return new UploadActivitySnapshot(session.uploadId(), session.status(),
                    snapshot.chunks().size(), session.expectedChunks(), session.packageId(),
                    session.createdAt(), session.updatedAt());
        });
    }

    void create(UploadSession session);

    void touch(UploadSnapshot snapshot);

    void save(UploadSession session);

    ChunkWriteResult recordChunk(String uploadId, ChunkMetadata metadata);

    void removeChunk(String uploadId, int index);

    void clearChunksAndActive(UploadSession session);

    void delete(UploadSnapshot snapshot);

    default List<String> findExpiredUploadIds(Instant now, int limit) {
        return List.of();
    }

    default void forgetUploadId(String uploadId) {
    }
}
