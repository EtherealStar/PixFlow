package com.pixflow.module.commerce.store;

import java.math.BigDecimal;

public record CommerceBenchmarkRow(
        String category,
        Long impressions,
        BigDecimal ctr,
        BigDecimal addCartRate,
        BigDecimal purchaseRate,
        Integer sampleCount) {
}
