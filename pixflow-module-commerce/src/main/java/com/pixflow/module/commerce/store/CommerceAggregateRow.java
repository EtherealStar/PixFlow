package com.pixflow.module.commerce.store;

import java.math.BigDecimal;
import java.time.Instant;

public record CommerceAggregateRow(
        String skuId,
        String category,
        Long impressions,
        BigDecimal ctr,
        BigDecimal addCartRate,
        BigDecimal purchaseRate,
        Instant fetchedAt,
        String source) {
}
