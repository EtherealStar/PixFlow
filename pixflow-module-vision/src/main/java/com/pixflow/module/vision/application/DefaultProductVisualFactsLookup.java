package com.pixflow.module.vision.application;

import com.pixflow.contracts.asset.AssetReferenceKey;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.contracts.asset.PackageAssetReferenceKey;
import com.pixflow.contracts.asset.SkuAssetReferenceKey;
import com.pixflow.module.vision.api.ProductVisualFacts;
import com.pixflow.module.vision.api.ProductVisualFactsLookup;
import com.pixflow.module.vision.api.VisualAsset;
import com.pixflow.module.vision.api.VisualAssetReader;
import com.pixflow.module.vision.api.VisualFactsLookupResult;
import com.pixflow.module.vision.api.VisualFactsLookupStatus;
import com.pixflow.module.vision.api.VisualFactsScope;
import com.pixflow.module.vision.domain.ProductVisualFactsNormalizer;
import com.pixflow.module.vision.domain.VisionInputStateStore;
import com.pixflow.module.vision.domain.VisionStateSnapshot;
import com.pixflow.module.vision.domain.VisionStateStore;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class DefaultProductVisualFactsLookup implements ProductVisualFactsLookup {
    private static final int PACKAGE_SCOPE_LIMIT = 20;

    private final CanonicalAssetReferenceCodec codec;

    private final VisionStateStore stateStore;

    private final VisionInputStateStore inputStore;

    private final VisualAssetReader assetReader;

    private final ProductVisualFactsNormalizer normalizer;

    private final FocusedImageAnalysis focusedImages;

    public DefaultProductVisualFactsLookup(
            CanonicalAssetReferenceCodec codec, VisionStateStore stateStore,
            VisionInputStateStore inputStore, VisualAssetReader assetReader,
            ProductVisualFactsNormalizer normalizer, FocusedImageAnalysis focusedImages) {
        this.codec = codec;
        this.stateStore = stateStore;
        this.inputStore = inputStore;
        this.assetReader = assetReader;
        this.normalizer = normalizer;
        this.focusedImages = focusedImages;
    }

    @Override
    public VisualFactsLookupResult lookup(String referenceKey) {
        AssetReferenceKey key = codec.parse(referenceKey);
        if (key instanceof SkuAssetReferenceKey sku) {
            return single(referenceKey, sku.packageId(), sku.skuId(), null);
        }
        if (key instanceof ImageAssetReferenceKey image) {
            VisualAsset asset = assetReader.requireImage(image.packageId(), image.imageId());
            return image(referenceKey, asset);
        }
        PackageAssetReferenceKey pkg = (PackageAssetReferenceKey) key;
        TreeSet<String> all = new TreeSet<>(inputStore.knownSkus(pkg.packageId()));
        List<VisualFactsScope> scopes = new ArrayList<>();
        for (String skuId : all.stream().limit(PACKAGE_SCOPE_LIMIT).toList()) {
            VisionStateSnapshot snapshot = stateStore.get(pkg.packageId(), skuId);
            scopes.add(scope(pkg.packageId(), skuId, null, snapshot));
        }
        boolean available = scopes.stream().anyMatch(scope -> scope.skuFacts() != null);
        return new VisualFactsLookupResult(
                available ? VisualFactsLookupStatus.AVAILABLE : VisualFactsLookupStatus.UNAVAILABLE,
                referenceKey, scopes, all.size() > PACKAGE_SCOPE_LIMIT,
                available ? null : "No current product visual facts are available");
    }

    private VisualFactsLookupResult image(String requestedKey, VisualAsset asset) {
        VisionStateSnapshot sku = stateStore.get(asset.packageId(), asset.skuId());
        FocusedImageAnalysis.Result focused = focusedImages.analyze(asset);
        ProductVisualFacts skuFacts = facts(sku);
        VisualFactsLookupStatus status;
        String reason = null;
        if (focused.facts() != null) {
            status = VisualFactsLookupStatus.AVAILABLE;
        } else if (focused.active()) {
            status = VisualFactsLookupStatus.ANALYSIS_PENDING;
        } else {
            status = VisualFactsLookupStatus.UNAVAILABLE;
            reason = "Target image visual analysis is unavailable";
        }
        VisualFactsScope scope = new VisualFactsScope(
                codec.serialize(new SkuAssetReferenceKey(asset.packageId(), asset.skuId())),
                requestedKey, skuFacts, focused.facts());
        return new VisualFactsLookupResult(status, requestedKey, List.of(scope), false, reason);
    }

    private VisualFactsLookupResult single(
            String requestedKey, long packageId, String skuId, String imageReferenceKey) {
        VisionStateSnapshot snapshot = stateStore.get(packageId, skuId);
        ProductVisualFacts facts = facts(snapshot);
        VisualFactsLookupStatus status;
        String reason = null;
        if (facts != null) {
            status = VisualFactsLookupStatus.AVAILABLE;
        } else if (snapshot.active()) {
            status = VisualFactsLookupStatus.ANALYSIS_PENDING;
        } else {
            status = VisualFactsLookupStatus.UNAVAILABLE;
            reason = "NO_IMAGE".equals(snapshot.failureCode())
                    ? "No current Original Image is available for this SKU"
                    : "Product visual analysis is unavailable";
        }
        return new VisualFactsLookupResult(status, requestedKey,
                List.of(scope(packageId, skuId, imageReferenceKey, snapshot)), false, reason);
    }

    private VisualFactsScope scope(
            long packageId, String skuId, String imageReferenceKey, VisionStateSnapshot snapshot) {
        return new VisualFactsScope(
                codec.serialize(new SkuAssetReferenceKey(packageId, skuId)),
                imageReferenceKey, facts(snapshot), null);
    }

    private ProductVisualFacts facts(VisionStateSnapshot snapshot) {
        return snapshot.factsJson() == null ? null : normalizer.read(snapshot.factsJson());
    }
}
