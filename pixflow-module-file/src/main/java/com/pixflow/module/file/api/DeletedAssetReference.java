package com.pixflow.module.file.api;

import com.pixflow.contracts.asset.AssetReferenceKind;

public record DeletedAssetReference(
        String referenceKey,
        AssetReferenceKind kind,
        String displayName) {
}
