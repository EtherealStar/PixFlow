package com.pixflow.contracts.asset;

/** Asset Package 的 canonical 身份。 */
public record PackageAssetReferenceKey(long packageId) implements AssetReferenceKey {

    public PackageAssetReferenceKey {
        AssetReferenceValidation.requirePositive(packageId, "packageId");
    }

    @Override
    public AssetReferenceKind kind() {
        return AssetReferenceKind.PACKAGE;
    }
}
