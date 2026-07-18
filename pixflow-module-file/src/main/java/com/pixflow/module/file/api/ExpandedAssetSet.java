package com.pixflow.module.file.api;

import java.util.List;

public record ExpandedAssetSet(List<ResolvedAssetReference> images) {
    public ExpandedAssetSet {
        images = images == null ? List.of() : List.copyOf(images);
    }
}
