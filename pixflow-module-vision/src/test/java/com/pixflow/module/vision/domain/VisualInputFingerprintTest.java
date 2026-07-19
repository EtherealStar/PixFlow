package com.pixflow.module.vision.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.vision.api.VisualAsset;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisualInputFingerprintTest {
    private final DeterministicVisualAssetSampler sampler = new DeterministicVisualAssetSampler();

    @Test
    void fingerprintDependsOnlyOnSortedContentHashes() {
        VisualAsset first = asset(11, "a".repeat(64));
        VisualAsset second = asset(22, "b".repeat(64));

        assertThat(VisualInputFingerprint.forSku(List.of(first, second)))
                .isEqualTo(VisualInputFingerprint.forSku(List.of(second, first)));
        assertThat(VisualInputFingerprint.forSku(List.of(first)))
                .isNotEqualTo(VisualInputFingerprint.forSku(List.of(first, second)));
    }

    @Test
    void samplingIsStableUniqueAndBoundedToTwoImages() {
        List<VisualAsset> assets = List.of(
                asset(11, "a".repeat(64)),
                asset(22, "b".repeat(64)),
                asset(33, "c".repeat(64)));
        String fingerprint = VisualInputFingerprint.forSku(assets);

        List<VisualAsset> selected = sampler.sample(7, "sku-1", fingerprint, assets);
        List<VisualAsset> replayed = sampler.sample(7, "sku-1", fingerprint, assets.reversed());

        assertThat(selected).hasSize(2).doesNotHaveDuplicates();
        assertThat(replayed).extracting(VisualAsset::imageId)
                .containsExactlyElementsOf(selected.stream().map(VisualAsset::imageId).toList());
    }

    @Test
    void emptyInputHasTheSha256OfEmptyBytes() {
        assertThat(VisualInputFingerprint.forSku(List.of()))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb924"
                        + "27ae41e4649b934ca495991b7852b855");
    }

    private VisualAsset asset(long imageId, String contentHash) {
        return new VisualAsset(
                7,
                "sku-1",
                imageId,
                contentHash,
                1,
                "image/png",
                () -> new ByteArrayInputStream(new byte[] {1}));
    }
}
