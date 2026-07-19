package com.pixflow.module.file.api;

import com.pixflow.contracts.asset.AssetReferenceKind;

public record AssetReferenceCandidate(
        String referenceKey,
        AssetReferenceKind kind,
        AssetSourceType sourceType,
        String displayPath,
        boolean hasChildren,
        AssetReferenceSource sourceGroup) {
}
