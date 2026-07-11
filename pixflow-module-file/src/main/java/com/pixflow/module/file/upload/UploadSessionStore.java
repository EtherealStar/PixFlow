package com.pixflow.module.file.upload;

import java.util.Optional;

/**
 * 上传会话的领域存储边界。调用方只处理快照和幂等结果，不感知 Redis key、Hash 或 TTL。
 */
public interface UploadSessionStore {
    Optional<UploadSnapshot> findByFileHash(String fileHash);

    Optional<UploadSnapshot> findByUploadId(String uploadId);

    void create(UploadSession session);

    void touch(UploadSnapshot snapshot);

    void save(UploadSession session);

    ChunkWriteResult recordChunk(String uploadId, ChunkMetadata metadata);

    void removeChunk(String uploadId, int index);

    void clearChunksAndActive(UploadSession session);

    void delete(UploadSnapshot snapshot);
}
