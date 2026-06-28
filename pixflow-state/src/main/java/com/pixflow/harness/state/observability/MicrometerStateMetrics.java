package com.pixflow.harness.state.observability;

import com.pixflow.harness.state.model.ProgressSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;

public class MicrometerStateMetrics implements StateMetrics {
    private final MeterRegistry registry;

    public MicrometerStateMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordSnapshot(String result, long nanos) {
        registry.timer("pixflow.state.snapshot", "result", result).record(Duration.ofNanos(nanos));
    }

    @Override
    public void recordProgressSource(ProgressSource source) {
        registry.counter("pixflow.state.progress.source", "source", source.name().toLowerCase()).increment();
    }

    @Override
    public void recordProgressDrift(long drift) {
        registry.summary("pixflow.state.progress.drift").record(drift);
    }

    @Override
    public void recordSkippableUnits(int count) {
        registry.summary("pixflow.state.recovery.skippable").record(count);
    }
}
