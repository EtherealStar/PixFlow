package com.pixflow.module.imagegen.port;

import java.util.Objects;

/**
 * 源图事实(imagegen 不直连 asset_* 表,经 SPI 取得)。
 *
 * <p>对齐 imagegen.md §四 / §五.1:由 {@code module/file} 实现 {@link SourceImageReader} 时,
 * 从 {@code asset_image} 表按 imageId 解析后返回本 record。
 */
public record SourceImageInfo(
        String imageId,
        String packageId,
        String skuId,
        String objectKey,
        String contentType,
        String viewId,
        String groupKey) {

    public SourceImageInfo {
        Objects.requireNonNull(imageId, "imageId");
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(contentType, "contentType");
    }
}