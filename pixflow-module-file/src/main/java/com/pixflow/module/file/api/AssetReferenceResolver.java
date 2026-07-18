package com.pixflow.module.file.api;

public interface AssetReferenceResolver {
    ResolvedAssetReference resolve(String referenceKey, AssetUse use);
}
