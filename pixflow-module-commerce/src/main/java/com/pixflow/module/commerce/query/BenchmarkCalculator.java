package com.pixflow.module.commerce.query;

import com.pixflow.module.commerce.store.CommerceBenchmarkRow;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class BenchmarkCalculator {
    public Benchmark calculate(Metrics skuMetrics, CommerceBenchmarkRow row, int minSample) {
        if (row == null) {
            return new Benchmark(null, null, 0, true);
        }
        boolean insufficient = row.sampleCount() == null || row.sampleCount() < minSample;
        Metrics average = new Metrics(
                row.impressions() == null ? 0L : row.impressions(),
                row.ctr(),
                row.addCartRate(),
                row.purchaseRate());
        if (insufficient) {
            return new Benchmark(average, null, row.sampleCount() == null ? 0 : row.sampleCount(), true);
        }
        return new Benchmark(
                average,
                new Deviation(
                        percent(skuMetrics.ctr(), row.ctr()),
                        percent(skuMetrics.addCartRate(), row.addCartRate()),
                        percent(skuMetrics.purchaseRate(), row.purchaseRate())),
                row.sampleCount(),
                false);
    }

    private static BigDecimal percent(BigDecimal value, BigDecimal baseline) {
        if (value == null || baseline == null || baseline.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return value.subtract(baseline)
                .divide(baseline, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
