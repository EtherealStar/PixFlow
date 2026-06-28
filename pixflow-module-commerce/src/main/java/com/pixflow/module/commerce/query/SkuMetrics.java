package com.pixflow.module.commerce.query;

import java.util.List;

public record SkuMetrics(
        String skuId,
        String category,
        Metrics aggregated,
        Benchmark benchmark,
        List<TrendPoint> trend,
        FreshnessInfo freshness) {
}
