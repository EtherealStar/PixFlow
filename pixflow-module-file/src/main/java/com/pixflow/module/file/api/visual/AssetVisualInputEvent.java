package com.pixflow.module.file.api.visual;

import java.time.Instant;

/** Asset Library 发出的中立视觉输入事实，不依赖 Vision 模块。 */
public record AssetVisualInputEvent(
        String eventId,
        Kind kind,
        long packageId,
        String skuId,
        Instant occurredAt) {
    public enum Kind {
        PACKAGE_READY,
        SKU_INPUT_CHANGED
    }
}
