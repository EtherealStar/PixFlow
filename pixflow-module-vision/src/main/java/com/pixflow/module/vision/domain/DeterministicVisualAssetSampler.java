package com.pixflow.module.vision.domain;

import com.pixflow.module.vision.api.VisualAsset;
import java.util.Comparator;
import java.util.List;

public final class DeterministicVisualAssetSampler {
    private static final int MAX_SAMPLE_SIZE = 2;

    public List<VisualAsset> sample(long packageId, String skuId, String fingerprint, List<VisualAsset> assets) {
        String seed = VisualInputFingerprint.sha256(packageId + "\0" + skuId + "\0" + fingerprint);
        return assets.stream()
                .sorted(Comparator
                        .comparing((VisualAsset asset) -> rank(seed, asset.imageId()))
                        .thenComparingLong(VisualAsset::imageId))
                .limit(MAX_SAMPLE_SIZE)
                .toList();
    }

    private String rank(String seed, long imageId) {
        return VisualInputFingerprint.sha256(seed + ":" + imageId);
    }
}
