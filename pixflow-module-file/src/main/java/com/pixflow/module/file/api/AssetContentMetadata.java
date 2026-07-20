package com.pixflow.module.file.api;

public record AssetContentMetadata(
        String referenceKey,
        long packageId,
        long imageId,
        String skuId,
        String groupKey,
        String viewId,
        AssetSourceType sourceType,
        String contentType,
        String contentHash,
        long size) {
}
