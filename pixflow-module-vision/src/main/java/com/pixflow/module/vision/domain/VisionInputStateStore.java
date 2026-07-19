package com.pixflow.module.vision.domain;

import java.time.Instant;
import java.util.Set;

/**
 * 把当前图片内容集合收敛为同一 SKU 的唯一 current work item。
 */
public interface VisionInputStateStore {
    Set<String> knownSkus(long packageId);

    InputReconciliation reconcileSkuInput(
            long packageId,
            String skuId,
            String fingerprint,
            boolean noImage,
            Instant now);
}
