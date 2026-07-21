package com.pixflow.module.file.internal.output;

import java.time.Instant;

public record GeneratedOutputImageRow(
        long imageId,
        long packageId,
        String skuId,
        String displayName,
        String conversationId,
        long taskId,
        String sourceImageId,
        Integer width,
        Integer height,
        Long sizeBytes,
        String contentType,
        String stableBucket,
        String minioKey,
        Instant createdAt) {
}
