package com.pixflow.module.file.pkg;

import java.time.Instant;

/** 面向上层 adapter 的素材包安全事实，不包含归档位置、hash 或清理状态。 */
public record MaterialPackageView(
        long packageId,
        String displayName,
        PackageStatus status,
        long originalImageCount,
        long skuCount,
        Instant createdAt,
        Instant updatedAt) {
}
