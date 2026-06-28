package com.pixflow.module.commerce.query;

public record Benchmark(
        Metrics categoryAverage,
        Deviation deviation,
        int sampleCount,
        boolean insufficientSample) {
}
