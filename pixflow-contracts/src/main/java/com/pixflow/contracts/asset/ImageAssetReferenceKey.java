package com.pixflow.contracts.asset;

/** Original Image 或 Generated Image 的 canonical 身份。 */
public record ImageAssetReferenceKey(long packageId, long imageId) implements AssetReferenceKey {

    public ImageAssetReferenceKey {
        AssetReferenceValidation.requirePositive(packageId, "packageId");
        AssetReferenceValidation.requirePositive(imageId, "imageId");
    }

    @Override
    public AssetReferenceKind kind() {
        return AssetReferenceKind.IMAGE;
    }
}
