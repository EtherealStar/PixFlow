package com.pixflow.module.file.upload;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.lock.LockTemplate;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageKeys;
import com.pixflow.module.file.config.FileProperties;
import com.pixflow.module.file.error.FileErrorCode;
import com.pixflow.module.file.ingest.ExtractionPublisher;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;

public class UploadSessionService {
    private static final String STATUS_UPLOADING = "UPLOADING";

    private static final String STATUS_COMPLETING = "COMPLETING";

    private static final String STATUS_READY = "READY";

    private static final String STATUS_CANCELLED = "CANCELLED";

    private static final Duration LOCK_WAIT = Duration.ofSeconds(5);

    private final UploadSessionStore store;

    private final LockTemplate lockTemplate;

    private final CacheNamespace namespace;

    private final ObjectStorage objectStorage;

    private final AssetPackageMapper packageMapper;

    private final AssetPackageService packageService;

    private final ExtractionPublisher extractionPublisher;

    private final FileProperties properties;

    private final Clock clock;

    public UploadSessionService(
            UploadSessionStore store,
            LockTemplate lockTemplate,
            CacheNamespace namespace,
            ObjectStorage objectStorage,
            AssetPackageMapper packageMapper,
            AssetPackageService packageService,
            ExtractionPublisher extractionPublisher,
            FileProperties properties,
            Clock clock) {
        this.store = store;
        this.lockTemplate = lockTemplate;
        this.namespace = namespace;
        this.objectStorage = objectStorage;
        this.packageMapper = packageMapper;
        this.packageService = packageService;
        this.extractionPublisher = extractionPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    public InitUploadResponse init(InitUploadRequest request) {
        validateInit(request);
        String fileHash = normalizeHash(request.fileHash());
        AssetPackage existing = findDedupReadyPackage(fileHash);
        if (existing != null) {
            return InitUploadResponse.dedupReady(existing.getId(), existing.getStatus().name());
        }
        return lockTemplate.runWithLock(namespace.key("upload", "lock", "filehash", fileHash), LOCK_WAIT, () -> {
            AssetPackage lockedExisting = findDedupReadyPackage(fileHash);
            if (lockedExisting != null) {
                return InitUploadResponse.dedupReady(lockedExisting.getId(), lockedExisting.getStatus().name());
            }
            var active = store.findByFileHash(fileHash);
            if (active.isPresent() && STATUS_UPLOADING.equals(active.get().session().status())) {
                store.touch(active.get());
                UploadSession session = active.get().session();
                return InitUploadResponse.resume(
                        session.uploadId(),
                        session.chunkSize(),
                        session.expectedChunks(),
                        uploadedIndexes(active.get()));
            }
            int expectedChunks = expectedChunks(request.size(), request.chunkSize());
            String uploadId = UUID.randomUUID().toString();
            UploadSession session = new UploadSession(uploadId, safeFilename(request.filename()), request.size(),
                    fileHash, request.chunkSize(), expectedChunks, STATUS_UPLOADING, null,
                    clock.instant(), clock.instant(), clock.instant().plus(properties.getUpload().getSessionTtl()));
            store.create(session);
            return InitUploadResponse.upload(uploadId, request.chunkSize(), expectedChunks, List.of());
        });
    }

    public UploadSessionState getSession(String uploadId) {
        return toState(requireSnapshot(uploadId));
    }

    public PutChunkResponse putChunk(
            String uploadId, int index, long declaredSize, String chunkHash, InputStream body) {
        UploadSession session = requireUploading(uploadId).session();
        validateChunkIndex(session, index);
        String normalizedHash = normalizeHash(chunkHash);
        long expectedSize = expectedChunkSize(session, index);
        if (declaredSize != expectedSize) {
            throw new PixFlowException(FileErrorCode.CHUNK_SIZE_MISMATCH, "chunk size mismatch");
        }
        return lockTemplate.runWithLock(
                namespace.key("upload", "lock", uploadId, "chunk", String.valueOf(index)),
                LOCK_WAIT,
                () -> {
            UploadSnapshot snapshot = requireUploading(uploadId);
            ChunkMetadata existing = snapshot.chunks().get(index);
            if (existing != null) {
                ObjectLocation existingLocation = ObjectLocation.of(BucketType.TMP, existing.minioKey());
                boolean validObject = objectStorage.exists(existingLocation)
                        && objectStorage.stat(existingLocation).size() == existing.chunkSize();
                if (validObject) {
                    if (!existing.chunkHash().equalsIgnoreCase(normalizedHash)) {
                        throw new PixFlowException(FileErrorCode.CHUNK_HASH_MISMATCH, "chunk hash mismatch");
                    }
                    return new PutChunkResponse(uploadId, index, "ALREADY_EXISTS", uploadedIndexes(snapshot));
                }
                // 元数据指向的临时对象已丢失或损坏，先使 field 失效，再允许确定性 key 被重传覆盖。
                store.removeChunk(uploadId, index);
            }
            // 分片阶段只写临时对象；complete 成功前不创建素材包事实源，便于取消和过期清理。
            String key = tmpChunkKey(uploadId, index);
            ChunkInputVerifier verifiedStream = new ChunkInputVerifier(body, expectedSize, normalizedHash);
            try {
                objectStorage.put(ObjectLocation.of(BucketType.TMP, key),
                        verifiedStream, expectedSize, "application/octet-stream");
                verifiedStream.verifyCompleted();
            } catch (PixFlowException ex) {
                objectStorage.delete(ObjectLocation.of(BucketType.TMP, key));
                throw ex;
            } catch (RuntimeException ex) {
                objectStorage.delete(ObjectLocation.of(BucketType.TMP, key));
                if (!verifiedStream.completed() && verifiedStream.bytesRead() > expectedSize) {
                    throw new PixFlowException(FileErrorCode.CHUNK_SIZE_MISMATCH, "chunk size mismatch", ex);
                }
                throw ex;
            }
            if (verifiedStream.bytesRead() != expectedSize) {
                objectStorage.delete(ObjectLocation.of(BucketType.TMP, key));
                throw new PixFlowException(FileErrorCode.CHUNK_SIZE_MISMATCH, "chunk size mismatch");
            }
            ChunkWriteResult result =
                    store.recordChunk(uploadId, new ChunkMetadata(index, normalizedHash, expectedSize, key));
            if (result == ChunkWriteResult.HASH_CONFLICT) {
                throw new PixFlowException(FileErrorCode.CHUNK_HASH_MISMATCH, "chunk hash mismatch");
            }
            UploadSnapshot updated = requireSnapshot(uploadId);
            String status = result == ChunkWriteResult.ALREADY_EXISTS ? "ALREADY_EXISTS" : "ACCEPTED";
            return new PutChunkResponse(uploadId, index, status, uploadedIndexes(updated));
                });
    }

    public CompleteUploadResponse complete(String uploadId, CompleteUploadRequest request) {
        return lockTemplate.runWithLock(terminalLock(uploadId), LOCK_WAIT, () -> {
            UploadSnapshot snapshot = requireSnapshot(uploadId);
            UploadSession session = snapshot.session();
            if (STATUS_READY.equals(session.status()) && session.packageId() != null) {
                AssetPackage existing = packageMapper.selectById(session.packageId());
                PackageStatus status = existing == null ? PackageStatus.UPLOADED : existing.getStatus();
                return new CompleteUploadResponse(session.packageId(), status);
            }
            if (!STATUS_UPLOADING.equals(session.status())) {
                throw new PixFlowException(
                        FileErrorCode.UPLOAD_SESSION_NOT_UPLOADING, "upload session is not uploading");
            }
            store.save(session.withStatus(STATUS_COMPLETING, session.packageId(), clock.instant()));
            AssetPackage assetPackage = null;
            ObjectLocation target = null;
            try {
                List<ChunkMetadata> chunks = loadAllChunks(snapshot);
                validateChunkSizes(session, chunks);
                String expectedHash = request == null || request.fileHash() == null || request.fileHash().isBlank()
                        ? session.fileHash()
                        : normalizeHash(request.fileHash());
                assetPackage = packageService.createUploadingPackage(session.filename(), session.fileHash());
                long packageId = assetPackage.getId();
                target = StorageKeys.packageSource(packageId, "zip");
                String actualHash = writeComposedObject(chunks, target, session.size());
                if (!actualHash.equalsIgnoreCase(expectedHash)) {
                    throw new PixFlowException(FileErrorCode.FILE_HASH_MISMATCH, "file hash mismatch");
                }
                // complete 是分片上传进入素材包事实源的边界：写 source.zip、落包记录、发解压消息。
                packageService.markSourceStored(packageId, target.key(), null);
                UploadSession ready = session.withStatus(STATUS_READY, packageId, clock.instant());
                store.save(ready);
                // READY 必须先于消息发布持久化，避免消费者看到尚未终结的上传会话。
                extractionPublisher.publish(packageId);
                objectStorage.deleteByPrefix(BucketType.TMP, uploadPrefix(uploadId));
                store.clearChunksAndActive(ready);
                return new CompleteUploadResponse(packageId, PackageStatus.UPLOADED);
            } catch (RuntimeException ex) {
                if (target != null) {
                    objectStorage.delete(target);
                }
                if (assetPackage != null) {
                    packageMapper.deleteById(assetPackage.getId());
                }
                // 终结任一步骤失败都恢复为可重试状态，已上传分片仍由原快照保留。
                store.save(session.withStatus(STATUS_UPLOADING, null, clock.instant()));
                throw ex;
            }
        });
    }

    public CancelUploadResponse cancel(String uploadId) {
        UploadSnapshot snapshot = store.findByUploadId(uploadId).orElse(null);
        if (snapshot == null) {
            return new CancelUploadResponse(uploadId, STATUS_CANCELLED);
        }
        return lockTemplate.runWithLock(terminalLock(uploadId), LOCK_WAIT, () -> {
            UploadSnapshot latest = store.findByUploadId(uploadId).orElse(snapshot);
            if (STATUS_READY.equals(latest.session().status())) {
                return new CancelUploadResponse(uploadId, STATUS_READY);
            }
            // 先清理对象前缀，失败时保留 Redis 状态，使取消操作可以安全重试。
            objectStorage.deleteByPrefix(BucketType.TMP, uploadPrefix(uploadId));
            store.delete(latest);
            return new CancelUploadResponse(uploadId, STATUS_CANCELLED);
        });
    }

    private UploadSessionState toState(UploadSnapshot snapshot) {
        UploadSession session = snapshot.session();
        return new UploadSessionState(session.uploadId(), session.fileHash(), session.size(), session.chunkSize(),
                session.expectedChunks(), uploadedIndexes(snapshot), List.of(), session.status(), session.packageId());
    }

    private UploadSnapshot requireSnapshot(String uploadId) {
        return store.findByUploadId(uploadId)
                .orElseThrow(() -> new PixFlowException(
                        FileErrorCode.UPLOAD_SESSION_NOT_FOUND, "upload session not found: " + uploadId));
    }

    private UploadSnapshot requireUploading(String uploadId) {
        UploadSnapshot snapshot = requireSnapshot(uploadId);
        if (!STATUS_UPLOADING.equals(snapshot.session().status())) {
            throw new PixFlowException(FileErrorCode.UPLOAD_SESSION_NOT_UPLOADING, "upload session is not uploading");
        }
        return snapshot;
    }

    private void validateInit(InitUploadRequest request) {
        if (request == null || request.filename() == null || request.filename().isBlank()) {
            throw new PixFlowException(FileErrorCode.FILE_HASH_MISMATCH, "filename is required");
        }
        if (request.size() <= 0 || request.size() > properties.getUpload().getMaxZipSize().toBytes()) {
            throw new PixFlowException(FileErrorCode.UPLOAD_TOO_LARGE, "file size out of range");
        }
        long expectedChunkSize = properties.getUpload().getChunkSize().toBytes();
        if (request.chunkSize() != expectedChunkSize) {
            throw new PixFlowException(FileErrorCode.CHUNK_SIZE_MISMATCH, "chunk size mismatch");
        }
        normalizeHash(request.fileHash());
    }

    private AssetPackage findDedupReadyPackage(String fileHash) {
        return packageMapper.selectOne(new LambdaQueryWrapper<AssetPackage>()
                .eq(AssetPackage::getFileHash, fileHash)
                .eq(AssetPackage::getStatus, PackageStatus.READY)
                .isNull(AssetPackage::getDeletedAt)
                .last("limit 1"));
    }

    private List<ChunkMetadata> loadAllChunks(UploadSnapshot snapshot) {
        UploadSession session = snapshot.session();
        List<Integer> missing = IntStream.range(0, session.expectedChunks())
                .filter(index -> !snapshot.chunks().containsKey(index))
                .boxed()
                .toList();
        if (!missing.isEmpty()) {
            throw new PixFlowException(FileErrorCode.INCOMPLETE_CHUNKS, "incomplete chunks: " + missing);
        }
        List<ChunkMetadata> chunks = new ArrayList<>(snapshot.chunks().values());
        for (ChunkMetadata chunk : chunks) {
            ObjectLocation location = ObjectLocation.of(BucketType.TMP, chunk.minioKey());
            if (!objectStorage.exists(location) || objectStorage.stat(location).size() != chunk.chunkSize()) {
                throw new PixFlowException(
                        FileErrorCode.INCOMPLETE_CHUNKS, "chunk object missing or invalid: " + chunk.index());
            }
        }
        return chunks;
    }

    private void validateChunkSizes(UploadSession session, List<ChunkMetadata> chunks) {
        long total = 0L;
        for (ChunkMetadata chunk : chunks) {
            long expected = expectedChunkSize(session, chunk.index());
            if (chunk.chunkSize() != expected) {
                throw new PixFlowException(FileErrorCode.CHUNK_SIZE_MISMATCH, "chunk size mismatch");
            }
            total += chunk.chunkSize();
        }
        if (total != session.size()) {
            throw new PixFlowException(FileErrorCode.CHUNK_SIZE_MISMATCH, "chunk size sum mismatch");
        }
    }

    private String writeComposedObject(List<ChunkMetadata> chunks, ObjectLocation target, long size) {
        try {
            List<InputStream> streams = chunks.stream()
                    .map(chunk -> objectStorage.getStream(ObjectLocation.of(BucketType.TMP, chunk.minioKey())))
                    .toList();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Enumeration<InputStream> enumeration = java.util.Collections.enumeration(streams);
            try (InputStream joined = new SequenceInputStream(enumeration);
                 DigestInputStream digestInputStream = new DigestInputStream(joined, digest)) {
                objectStorage.put(target, digestInputStream, size, "application/zip");
            } finally {
                for (InputStream stream : streams) {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                        // ignore
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException ex) {
            throw new PixFlowException(FileErrorCode.FILE_HASH_MISMATCH, "compose upload failed", ex);
        }
    }

    private static List<Integer> uploadedIndexes(UploadSnapshot snapshot) {
        return List.copyOf(snapshot.chunks().keySet());
    }

    private static int expectedChunks(long size, long chunkSize) {
        return (int) ((size + chunkSize - 1) / chunkSize);
    }

    private static long expectedChunkSize(UploadSession session, int index) {
        long offset = (long) index * session.chunkSize();
        long remaining = session.size() - offset;
        return Math.min(session.chunkSize(), remaining);
    }

    private static void validateChunkIndex(UploadSession session, int index) {
        if (index < 0 || index >= session.expectedChunks()) {
            throw new PixFlowException(FileErrorCode.CHUNK_OUT_OF_RANGE, "chunk index out of range");
        }
    }

    private static String normalizeHash(String value) {
        if (value == null) {
            throw new PixFlowException(FileErrorCode.FILE_HASH_MISMATCH, "hash is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new PixFlowException(FileErrorCode.FILE_HASH_MISMATCH, "hash is invalid");
        }
        return normalized;
    }

    private static String safeFilename(String filename) {
        String normalized = filename.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String tmpChunkKey(String uploadId, int index) {
        return "uploads/" + uploadId + "/chunks/" + index;
    }

    private static String uploadPrefix(String uploadId) {
        return "uploads/" + uploadId + "/";
    }

    private com.pixflow.infra.cache.key.CacheKey terminalLock(String uploadId) {
        return namespace.key("upload", "lock", uploadId, "terminal");
    }
}
