package com.pixflow.module.commerce.source;

import com.pixflow.module.commerce.query.PeriodType;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PlatformMetricRow(
        String skuId,
        String category,
        long impressions,
        BigDecimal ctr,
        BigDecimal addCartRate,
        BigDecimal purchaseRate,
        PeriodType periodType,
        LocalDate periodStart,
        LocalDate periodEnd) {
}
