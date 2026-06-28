package com.pixflow.module.commerce.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.commerce.store.CommerceBenchmarkRow;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BenchmarkCalculatorTest {
    private final BenchmarkCalculator calculator = new BenchmarkCalculator();

    @Test
    void calculatesSignedDeviationPercent() {
        Metrics sku = new Metrics(100, new BigDecimal("0.10"), new BigDecimal("0.04"), new BigDecimal("0.02"));
        CommerceBenchmarkRow row = new CommerceBenchmarkRow(
                "dress",
                600L,
                new BigDecimal("0.20"),
                new BigDecimal("0.02"),
                new BigDecimal("0.02"),
                6);

        Benchmark benchmark = calculator.calculate(sku, row, 5);

        assertThat(benchmark.insufficientSample()).isFalse();
        assertThat(benchmark.deviation().ctrPercent()).isEqualByComparingTo("-50.000000");
        assertThat(benchmark.deviation().addCartRatePercent()).isEqualByComparingTo("100.000000");
    }

    @Test
    void marksInsufficientSampleWithoutFakeDeviation() {
        Metrics sku = new Metrics(100, new BigDecimal("0.10"), new BigDecimal("0.04"), new BigDecimal("0.02"));
        CommerceBenchmarkRow row = new CommerceBenchmarkRow("dress", 100L, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 2);

        Benchmark benchmark = calculator.calculate(sku, row, 5);

        assertThat(benchmark.insufficientSample()).isTrue();
        assertThat(benchmark.deviation()).isNull();
    }
}
