package com.pixflow.module.vision.execution;

import com.pixflow.module.vision.application.VisionAnalysisJobCoordinator;

/** MQ adapter 调用的窄入口；重复消息由 current-row 状态机吸收。 */
public final class VisionMessageHandlers {
    private final VisionAnalysisJobCoordinator coordinator;

    private final VisionFactsWorker worker;

    public VisionMessageHandlers(VisionAnalysisJobCoordinator coordinator, VisionFactsWorker worker) {
        this.coordinator = coordinator;
        this.worker = worker;
    }

    public void packageRequested(long packageId) {
        coordinator.coordinatePackage(packageId);
    }

    public void skuInputChanged(long packageId, String skuId) {
        coordinator.coordinateSku(packageId, skuId);
    }

    public void analyzeItem(long itemId) {
        worker.execute(itemId);
    }
}
