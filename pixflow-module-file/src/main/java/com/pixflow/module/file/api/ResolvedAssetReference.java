package com.pixflow.module.file.api;

import com.pixflow.contracts.asset.AssetReferenceKind;
import java.util.Objects;

/** 不泄露对象存储位置的资产事实视图。 */
public record ResolvedAssetReference(
        String referenceKey,
        AssetReferenceKind kind,
        AssetSourceType sourceType,
        long packageId,
        Long imageId,
        String skuId,
        String displayPath) {
    public ResolvedAssetReference {
        referenceKey = Objects.requireNonNull(referenceKey, "referenceKey");
        kind = Objects.requireNonNull(kind, "kind");
        displayPath = Objects.requireNonNull(displayPath, "displayPath");
        if (kind == AssetReferenceKind.IMAGE && (sourceType == null || imageId == null)) {
            throw new IllegalArgumentException("IMAGE reference requires sourceType and imageId");
        }
        if (kind != AssetReferenceKind.IMAGE && sourceType != null) {
            throw new IllegalArgumentException("sourceType belongs to IMAGE references only");
        }
    }
}
