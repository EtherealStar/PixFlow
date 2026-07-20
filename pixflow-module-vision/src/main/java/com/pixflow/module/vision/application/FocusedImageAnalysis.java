package com.pixflow.module.vision.application;

import com.pixflow.module.vision.api.ProductVisualFacts;
import com.pixflow.module.vision.api.VisualAsset;
import com.pixflow.module.vision.domain.ProductVisualFactsNormalizer;
import com.pixflow.module.vision.execution.VisionExecutionStore;
import com.pixflow.module.vision.execution.VisionFactsWorker;
import com.pixflow.module.vision.execution.VisionWorkItem;
import java.time.Clock;
import java.util.Objects;

/** 显式 IMAGE lookup 的同步补偿入口。 */
public final class FocusedImageAnalysis {
    private final VisionExecutionStore store;

    private final VisionFactsWorker worker;

    private final ProductVisualFactsNormalizer normalizer;

    private final Clock clock;

    public FocusedImageAnalysis(
            VisionExecutionStore store, VisionFactsWorker worker,
            ProductVisualFactsNormalizer normalizer, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result analyze(VisualAsset asset) {
        VisionWorkItem item = store.ensureImageWork(asset, clock.instant());
        ProductVisualFacts current = read(asset);
        if (current != null) {
            return new Result(current, false);
        }
        if ("PENDING".equals(item.status()) || "EXPIRED".equals(item.status())) {
            worker.execute(item.id());
        }
        VisionWorkItem refreshed = store.get(item.id());
        current = read(asset);
        boolean active = current == null && refreshed != null
                && ("PENDING".equals(refreshed.status())
                || "RUNNING".equals(refreshed.status())
                || "EXPIRED".equals(refreshed.status()));
        return new Result(current, active);
    }

    private ProductVisualFacts read(VisualAsset asset) {
        String json = store.currentImageFacts(
                asset.packageId(), asset.skuId(), asset.imageId(), asset.contentHash());
        return json == null ? null : normalizer.read(json);
    }

    public record Result(ProductVisualFacts facts, boolean active) { }
}
