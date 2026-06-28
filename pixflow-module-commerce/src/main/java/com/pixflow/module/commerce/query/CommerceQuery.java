package com.pixflow.module.commerce.query;

import java.util.List;

public record CommerceQuery(
        List<String> skuIds,
        TimeWindow window,
        PeriodType periodType,
        boolean withBenchmark,
        boolean withTrend,
        CommerceSourceScope sourceScope,
        String preferredSource) {
}
