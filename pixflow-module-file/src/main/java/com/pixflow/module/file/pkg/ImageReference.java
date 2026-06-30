package com.pixflow.module.file.pkg;

/**
 * 素材包内单张图片的稳定引用。
 */
public record ImageReference(
        String imageId,
        String objectKey,
        String originalPath,
        String skuId,
        String groupKey,
        String viewId) {
}
