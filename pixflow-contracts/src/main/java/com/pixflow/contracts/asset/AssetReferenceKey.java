package com.pixflow.contracts.asset;

/** 只表达稳定身份，不包含资源存在性、权限或可处理状态。 */
public sealed interface AssetReferenceKey
        permits PackageAssetReferenceKey, SkuAssetReferenceKey, ImageAssetReferenceKey {

    AssetReferenceKind kind();

    long packageId();
}
