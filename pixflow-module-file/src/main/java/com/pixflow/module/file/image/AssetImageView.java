package com.pixflow.module.file.image;

import java.net.URL;
import java.time.Instant;

public record AssetImageView(
        String imageId,
        long packageId,
        String filename,
        String displayName,
        String originalPath,
        String skuId,
        String groupKey,
        String viewId,
        Long size,
        URL url,
        Instant createdAt) {
}
