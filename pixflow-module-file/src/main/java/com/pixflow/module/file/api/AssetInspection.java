package com.pixflow.module.file.api;

import java.util.List;

public record AssetInspection(
        ResolvedAssetReference reference,
        List<ResolvedAssetReference> children) {
    public AssetInspection {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
