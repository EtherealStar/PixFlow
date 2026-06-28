package com.pixflow.module.commerce.query;

import java.math.BigDecimal;

public record Deviation(
        BigDecimal ctrPercent,
        BigDecimal addCartRatePercent,
        BigDecimal purchaseRatePercent) {
}
