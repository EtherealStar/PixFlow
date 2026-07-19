package com.pixflow.module.file.internal.deletion;

import com.pixflow.contracts.asset.AssetReferenceKey;
import com.pixflow.contracts.asset.AssetReferenceKind;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.contracts.asset.PackageAssetReferenceKey;
import com.pixflow.contracts.asset.SkuAssetReferenceKey;
import com.pixflow.module.file.api.AssetReferenceHistory;
import com.pixflow.module.file.api.DeletedAssetReference;
import java.util.Optional;

public final class DefaultAssetReferenceHistory implements AssetReferenceHistory {
    private final CanonicalAssetReferenceCodec codec;

    private final AssetReferenceTombstoneMapper mapper;

    public DefaultAssetReferenceHistory(
            CanonicalAssetReferenceCodec codec, AssetReferenceTombstoneMapper mapper) {
        this.codec = codec;
        this.mapper = mapper;
    }

    @Override
    public Optional<DeletedAssetReference> findDeleted(String referenceKey) {
        AssetReferenceKey key = codec.parse(referenceKey);
        String skuId = key instanceof SkuAssetReferenceKey sku ? sku.skuId() : "";
        long imageId = key instanceof ImageAssetReferenceKey image ? image.imageId() : 0L;
        AssetReferenceKind kind = key instanceof PackageAssetReferenceKey
                ? AssetReferenceKind.PACKAGE
                : key instanceof SkuAssetReferenceKey ? AssetReferenceKind.SKU : AssetReferenceKind.IMAGE;
        AssetReferenceTombstone tombstone = mapper.findIdentity(
                kind.name(), key.packageId(), skuId, imageId);
        if (tombstone == null) {
            return Optional.empty();
        }
        return Optional.of(new DeletedAssetReference(
                codec.serialize(key), kind, tombstone.getDisplayName()));
    }
}
