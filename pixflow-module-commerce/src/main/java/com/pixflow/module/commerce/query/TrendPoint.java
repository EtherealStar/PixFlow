package com.pixflow.module.commerce.query;

import java.time.LocalDate;

public record TrendPoint(
        LocalDate periodStart,
        LocalDate periodEnd,
        Metrics metrics) {
}
