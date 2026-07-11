package com.pixflow.module.file.upload;

import java.time.Instant;

record UploadSession(
        String uploadId,
        String filename,
        long size,
        String fileHash,
        long chunkSize,
        int expectedChunks,
        String status,
        Long packageId,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt) {
    UploadSession withStatus(String nextStatus, Long nextPackageId, Instant now) {
        return new UploadSession(uploadId, filename, size, fileHash, chunkSize, expectedChunks,
                nextStatus, nextPackageId, createdAt, now, expiresAt);
    }

    UploadSession withExpiry(Instant nextExpiresAt) {
        return new UploadSession(uploadId, filename, size, fileHash, chunkSize, expectedChunks,
                status, packageId, createdAt, updatedAt, nextExpiresAt);
    }
}
