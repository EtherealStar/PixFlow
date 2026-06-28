package com.pixflow.infra.vector.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;

public class MicrometerVectorMetrics implements VectorMetrics {
    private final MeterRegistry meterRegistry;

    public MicrometerVectorMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordOperation(String operation, String result, Duration duration) {
        meterRegistry.timer("pixflow.vector.op", "op", operation, "result", result).record(duration);
    }

    @Override
    public void recordSearchReturned(int count) {
        meterRegistry.summary("pixflow.vector.search.returned").record(count);
    }
}
