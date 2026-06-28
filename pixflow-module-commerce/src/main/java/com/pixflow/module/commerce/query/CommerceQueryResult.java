package com.pixflow.module.commerce.query;

import java.util.List;

public record CommerceQueryResult(
        List<SkuMetrics> perSku,
        List<String> missingSkus,
        boolean degraded) {
}
