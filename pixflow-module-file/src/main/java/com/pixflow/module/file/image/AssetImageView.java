package com.pixflow.module.file.image;

import java.net.URL;
import java.time.Instant;
import com.pixflow.module.file.api.AssetSourceType;

public record AssetImageView(
        String imageId,
        long packageId,
        String referenceKey,
        AssetSourceType sourceType,
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
