package com.pixflow.module.vision.api;

import java.time.Instant;

/**
 * 管理员读取视图。事实是否存在与分析状态彼此独立。
 */
public record VisualFactsView(
        long packageId,
        String skuId,
        AnalysisStatus analysisStatus,
        long analysisGeneration,
        ProductVisualFacts facts,
        long version,
        VisualFactsWriter writer,
        Instant updatedAt,
        String failureCode) {
}
