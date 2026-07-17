package com.pixflow.contracts.asset;

/** Asset Package 内 SKU scope 的 canonical 身份。 */
public record SkuAssetReferenceKey(long packageId, String skuId) implements AssetReferenceKey {

    public SkuAssetReferenceKey {
        AssetReferenceValidation.requirePositive(packageId, "packageId");
        AssetReferenceValidation.requireSkuId(skuId);
    }

    @Override
    public AssetReferenceKind kind() {
        return AssetReferenceKind.SKU;
    }
}
