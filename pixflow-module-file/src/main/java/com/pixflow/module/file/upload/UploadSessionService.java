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
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
            var activeUploadId = store.findActiveUploadId(fileHash);
            if (activeUploadId.isPresent()) {
                var activeSession = store.find(activeUploadId.get());
                if (activeSession.isPresent() && STATUS_UPLOADING.equals(activeSession.get().status())) {
                    // 同一 fileHash 只能有一个上传事实源；并发 init 复用已有会话，避免重复写临时分片。
                    return InitUploadResponse.dedupUploading(activeUploadId.get(), store.uploadedChunks(activeSession.get()));
                }
            }
            int expectedChunks = expectedChunks(request.size(), request.chunkSize());
            String uploadId = UUID.randomUUID().toString();
            UploadSession session = new UploadSession(uploadId, safeFilename(request.filename()), request.size(),
                    fileHash, request.chunkSize(), expectedChunks, STATUS_UPLOADING, null, clock.instant(), clock.instant());
            store.save(session);
            return InitUploadResponse.upload(uploadId, request.chunkSize(), expectedChunks, List.of());
        });
    }

    public UploadSessionState getSession(String uploadId) {
        UploadSession session = require(uploadId);
        return toState(session);
    }

    public PutChunkResponse putChunk(String uploadId, int index, long declaredSize, String chunkHash, InputStream body) {
        UploadSession session = requireUploading(uploadId);
        validateChunkIndex(session, index);
        String normalizedHash = normalizeHash(chunkHash);
        long expectedSize = expectedChunkSize(session, index);
        if (declaredSize != expectedSize) {
            throw new PixFlowException(FileErrorCode.CHUNK_SIZE_MISMATCH, "chunk size mismatch");
        }
        return lockTemplate.runWithLock(namespace.key("upload", "lock", uploadId, "chunk", String.valueOf(index)), LOCK_WAIT, () -> {
            var existing = store.findChunk(uploadId, index);
            if (existing.isPresent()) {
                if (!existing.get().chunkHash().equalsIgnoreCase(normalizedHash)) {
                    throw new PixFlowException(FileErrorCode.CHUNK_HASH_MISMATCH, "chunk hash mismatch");
                }
                return new PutChunkResponse(uploadId, index, "ALREADY_EXISTS", store.uploadedChunks(session));
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
            store.saveChunk(uploadId, new ChunkMetadata(index, normalizedHash, expectedSize, key));
            return new PutChunkResponse(uploadId, index, "ACCEPTED", store.uploadedChunks(session));
        });
    }

    public CompleteUploadResponse complete(String uploadId, CompleteUploadRequest request) {
        return lockTemplate.runWithLock(terminalLock(uploadId), LOCK_WAIT, () -> {
            UploadSession session = require(uploadId);
            if (STATUS_READY.equals(session.status()) && session.packageId() != null) {
                AssetPackage existing = packageMapper.selectById(session.packageId());
                PackageStatus status = existing == null ? PackageStatus.UPLOADED : existing.getStatus();
                return new CompleteUploadResponse(session.packageId(), status);
            }
            if (!STATUS_UPLOADING.equals(session.status())) {
                throw new PixFlowException(FileErrorCode.UPLOAD_SESSION_NOT_UPLOADING, "upload session is not uploading");
            }
            store.save(session.withStatus(STATUS_COMPLETING, session.packageId(), clock.instant()));
            List<ChunkMetadata> chunks = loadAllChunks(session);
            validateChunkSizes(session, chunks);
            String expectedHash = request == null || request.fileHash() == null || request.fileHash().isBlank()
                    ? session.fileHash()
                    : normalizeHash(request.fileHash());
            AssetPackage assetPackage = packageService.createUploadingPackage(session.filename(), session.fileHash());
            long packageId = assetPackage.getId();
            ObjectLocation target = StorageKeys.packageSource(packageId);
            try {
                String actualHash = writeComposedObject(chunks, target, session.size());
                if (!actualHash.equalsIgnoreCase(expectedHash)) {
                    throw new PixFlowException(FileErrorCode.FILE_HASH_MISMATCH, "file hash mismatch");
                }
                // complete 是分片上传进入素材包事实源的边界：写 source.zip、落包记录、发解压消息。
                packageService.markSourceStored(packageId, target.key(), null);
            } catch (PixFlowException ex) {
                objectStorage.delete(target);
                packageMapper.deleteById(packageId);
                store.save(session.withStatus(STATUS_UPLOADING, null, clock.instant()));
                throw ex;
            } catch (RuntimeException ex) {
                objectStorage.delete(target);
                packageMapper.deleteById(packageId);
                store.save(session.withStatus(STATUS_UPLOADING, null, clock.instant()));
                throw ex;
            }
            var publishResult = extractionPublisher.publish(packageId);
            UploadSession ready = session.withStatus(STATUS_READY, packageId, clock.instant());
            store.save(ready);
            cleanupChunks(session, chunks, false);
            return new CompleteUploadResponse(packageId, PackageStatus.UPLOADED);
        });
    }

    public CancelUploadResponse cancel(String uploadId) {
        UploadSession session = store.find(uploadId).orElse(null);
        if (session == null) {
            return new CancelUploadResponse(uploadId, STATUS_CANCELLED);
        }
        return lockTemplate.runWithLock(terminalLock(uploadId), LOCK_WAIT, () -> {
            UploadSession latest = store.find(uploadId).orElse(session);
            if (STATUS_READY.equals(latest.status())) {
                return new CancelUploadResponse(uploadId, STATUS_READY);
            }
            List<ChunkMetadata> chunks = existingChunks(latest);
            cleanupChunks(latest, chunks, true);
            return new CancelUploadResponse(uploadId, STATUS_CANCELLED);
        });
    }

    private UploadSessionState toState(UploadSession session) {
        return new UploadSessionState(session.uploadId(), session.fileHash(), session.size(), session.chunkSize(),
                session.expectedChunks(), store.uploadedChunks(session), List.of(), session.status(), session.packageId());
    }

    private UploadSession require(String uploadId) {
        return store.find(uploadId)
                .orElseThrow(() -> new PixFlowException(FileErrorCode.UPLOAD_SESSION_NOT_FOUND, "upload session not found: " + uploadId));
    }

    private UploadSession requireUploading(String uploadId) {
        UploadSession session = require(uploadId);
        if (!STATUS_UPLOADING.equals(session.status())) {
            throw new PixFlowException(FileErrorCode.UPLOAD_SESSION_NOT_UPLOADING, "upload session is not uploading");
        }
        return session;
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

    private List<ChunkMetadata> loadAllChunks(UploadSession session) {
        List<ChunkMetadata> chunks = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < session.expectedChunks(); i++) {
            var chunk = store.findChunk(session.uploadId(), i);
            if (chunk.isPresent()) chunks.add(chunk.get());
            else missing.add(i);
        }
        if (!missing.isEmpty()) {
            throw new PixFlowException(FileErrorCode.INCOMPLETE_CHUNKS, "incomplete chunks: " + missing);
        }
        chunks.sort(Comparator.comparingInt(ChunkMetadata::index));
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

    private List<ChunkMetadata> existingChunks(UploadSession session) {
        List<ChunkMetadata> chunks = new ArrayList<>();
        for (int i = 0; i < session.expectedChunks(); i++) {
            store.findChunk(session.uploadId(), i).ifPresent(chunks::add);
        }
        return chunks;
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

    private void cleanupChunks(UploadSession session, List<ChunkMetadata> chunks, boolean deleteSession) {
        for (ChunkMetadata chunk : chunks) {
            objectStorage.delete(ObjectLocation.of(BucketType.TMP, chunk.minioKey()));
        }
        if (deleteSession) {
            store.deleteSession(session);
        } else {
            store.deleteChunks(session.uploadId(), session.expectedChunks());
            store.deleteActiveUpload(session);
        }
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

    private com.pixflow.infra.cache.key.CacheKey terminalLock(String uploadId) {
        return namespace.key("upload", "lock", uploadId, "terminal");
    }
}
