package com.pixflow.module.file.api.activity;

import java.time.Instant;
import java.util.Objects;

public record UploadActivitySnapshot(
        String uploadId,
        String status,
        int completedChunks,
        int expectedChunks,
        Long packageId,
        Instant createdAt,
        Instant updatedAt) {
    public UploadActivitySnapshot {
        uploadId = Objects.requireNonNull(uploadId, "uploadId");
        status = Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (uploadId.isBlank() || completedChunks < 0 || expectedChunks < 0
                || completedChunks > expectedChunks) {
            throw new IllegalArgumentException("invalid upload activity snapshot");
        }
    }
}
