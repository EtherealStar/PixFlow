package com.pixflow.module.vision.application;

import com.pixflow.module.vision.api.VisualAsset;
import com.pixflow.module.vision.api.VisualAssetReader;
import com.pixflow.module.vision.domain.InputReconciliation;
import com.pixflow.module.vision.domain.VisionInputStateStore;
import com.pixflow.module.vision.domain.VisualInputFingerprint;
import com.pixflow.module.vision.execution.VisionWorkPublisher;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class VisionAnalysisJobCoordinator {
    private final VisualAssetReader assetReader;

    private final VisionInputStateStore stateStore;

    private final VisionWorkPublisher workPublisher;

    private final Clock clock;

    public VisionAnalysisJobCoordinator(
            VisualAssetReader assetReader,
            VisionInputStateStore stateStore,
            VisionWorkPublisher workPublisher,
            Clock clock) {
        this.assetReader = Objects.requireNonNull(assetReader, "assetReader");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.workPublisher = Objects.requireNonNull(workPublisher, "workPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<Long> coordinatePackage(long packageId) {
        if (packageId <= 0) {
            throw new IllegalArgumentException("packageId must be positive");
        }
        Map<String, List<VisualAsset>> assetsBySku = assetReader.listCurrentOriginals(packageId).stream()
                .collect(Collectors.groupingBy(
                        VisualAsset::skuId,
                        TreeMap::new,
                        Collectors.toList()));
        Set<String> scopes = new TreeSet<>(stateStore.knownSkus(packageId));
        scopes.addAll(assetsBySku.keySet());

        List<Long> queued = new ArrayList<>();
        for (String skuId : scopes) {
            List<VisualAsset> assets = assetsBySku.getOrDefault(skuId, List.of());
            reconcile(packageId, skuId, assets, queued);
        }
        return List.copyOf(queued);
    }

    public List<Long> coordinateSku(long packageId, String skuId) {
        if (packageId <= 0 || skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("packageId and skuId must identify a SKU scope");
        }
        List<VisualAsset> assets = assetReader.listCurrentOriginals(packageId).stream()
                .filter(asset -> skuId.equals(asset.skuId()))
                .toList();
        List<Long> queued = new ArrayList<>();
        reconcile(packageId, skuId, assets, queued);
        return List.copyOf(queued);
    }

    private void reconcile(
            long packageId,
            String skuId,
            List<VisualAsset> assets,
            List<Long> queued) {
        InputReconciliation result = stateStore.reconcileSkuInput(
                packageId,
                skuId,
                VisualInputFingerprint.forSku(assets),
                assets.isEmpty(),
                clock.instant());
        if (result.analysisQueued()) {
            // 先持久化 PENDING，再发布消息；发布缺口由后续 PENDING 扫描恢复。
            workPublisher.publish(result.itemId());
            queued.add(result.itemId());
        }
    }
}
