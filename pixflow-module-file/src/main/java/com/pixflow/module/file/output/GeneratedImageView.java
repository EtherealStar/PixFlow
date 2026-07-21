package com.pixflow.module.file.output;

import java.time.Instant;

public record GeneratedImageView(
        String imageId,
        String referenceKey,
        String sourceType,
        String displayName,
        long packageId,
        String skuId,
        String conversationId,
        String taskId,
        String sourceImageId,
        Integer width,
        Integer height,
        Long sizeBytes,
        String contentType,
        String previewUrl,
        Instant previewExpiresAt,
        Instant createdAt) {
}
