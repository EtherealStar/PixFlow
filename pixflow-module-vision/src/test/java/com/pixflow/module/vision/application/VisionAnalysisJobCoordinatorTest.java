package com.pixflow.module.vision.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.vision.api.VisualAsset;
import com.pixflow.module.vision.api.VisualAssetReader;
import com.pixflow.module.vision.domain.InputReconciliation;
import com.pixflow.module.vision.domain.VisionInputStateStore;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VisionAnalysisJobCoordinatorTest {
    @Test
    void packageIncludesKnownSkuWhoseLastImageWasRemoved() {
        FakeStateStore store = new FakeStateStore(Set.of("removed", "present"));
        List<Long> published = new ArrayList<>();
        VisualAssetReader reader = reader(List.of(asset("present", 1, "a".repeat(64))));
        VisionAnalysisJobCoordinator coordinator = coordinator(reader, store, published);

        assertThat(coordinator.coordinatePackage(7)).containsExactly(1L);
        assertThat(published).containsExactly(1L);
        assertThat(store.noImageScopes).containsExactly("removed");
    }

    @Test
    void unchangedFingerprintDoesNotPublishASecondWorkMessage() {
        FakeStateStore store = new FakeStateStore(Set.of());
        store.queueNext = false;
        List<Long> published = new ArrayList<>();
        VisualAssetReader reader = reader(List.of(asset("sku", 1, "a".repeat(64))));
        VisionAnalysisJobCoordinator coordinator = coordinator(reader, store, published);

        assertThat(coordinator.coordinateSku(7, "sku")).isEmpty();
        assertThat(published).isEmpty();
    }

    private VisionAnalysisJobCoordinator coordinator(
            VisualAssetReader reader,
            VisionInputStateStore store,
            List<Long> published) {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC);
        return new VisionAnalysisJobCoordinator(reader, store, published::add, clock);
    }

    private VisualAssetReader reader(List<VisualAsset> assets) {
        return new VisualAssetReader() {
            @Override
            public List<VisualAsset> listCurrentOriginals(long packageId) {
                return assets;
            }

            @Override
            public VisualAsset requireImage(long packageId, long imageId) {
                throw new UnsupportedOperationException("not used");
            }
        };
    }

    private VisualAsset asset(String skuId, long imageId, String hash) {
        return new VisualAsset(
                7,
                skuId,
                imageId,
                hash,
                1,
                "image/png",
                () -> new ByteArrayInputStream(new byte[] {1}));
    }

    private static final class FakeStateStore implements VisionInputStateStore {
        private final Set<String> knownSkus;

        private final Set<String> noImageScopes = new HashSet<>();

        private boolean queueNext = true;

        private FakeStateStore(Set<String> knownSkus) {
            this.knownSkus = knownSkus;
        }

        @Override
        public Set<String> knownSkus(long packageId) {
            return knownSkus;
        }

        @Override
        public InputReconciliation reconcileSkuInput(
                long packageId,
                String skuId,
                String fingerprint,
                boolean noImage,
                Instant now) {
            if (noImage) {
                noImageScopes.add(skuId);
                return new InputReconciliation(0, false);
            }
            return new InputReconciliation(1, queueNext);
        }
    }
}
