package com.pixflow.module.commerce.query;

import java.math.BigDecimal;

public record Metrics(
        long impressions,
        BigDecimal ctr,
        BigDecimal addCartRate,
        BigDecimal purchaseRate) {
}
