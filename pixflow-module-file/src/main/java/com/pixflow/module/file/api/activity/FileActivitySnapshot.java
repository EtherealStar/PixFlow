package com.pixflow.module.file.api.activity;

import java.time.Instant;
import java.util.Objects;

public record FileActivitySnapshot(
        FileActivitySourceKind sourceKind,
        String sourceId,
        long revision,
        FileActivityStatus status,
        int completed,
        int total,
        Long packageId,
        Instant createdAt,
        Instant updatedAt,
        boolean cancellable,
        boolean clearable) {
    public FileActivitySnapshot {
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (sourceId.isBlank() || revision < 0 || completed < 0 || total < 0 || completed > total) {
            throw new IllegalArgumentException("invalid file activity snapshot");
        }
    }
}
