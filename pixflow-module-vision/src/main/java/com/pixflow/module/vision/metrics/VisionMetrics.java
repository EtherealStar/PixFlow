package com.pixflow.module.vision.metrics;

import com.pixflow.module.vision.analyze.VisionTaskType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

public class VisionMetrics {
    private final MeterRegistry meterRegistry;

    public VisionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    public void recordAnalyze(VisionTaskType taskType, boolean degraded) {
        Counter.builder("pixflow.vision.analyze")
                .tag("taskType", taskType == null ? "UNKNOWN" : taskType.name())
                .tag("parse", degraded ? "degraded" : "ok")
                .register(meterRegistry)
                .increment();
    }

    public void recordImages(String stage, int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("pixflow.vision.images")
                .tag("stage", stage)
                .register(meterRegistry)
                .increment(count);
    }

    public void recordEnrich(String result) {
        Counter.builder("pixflow.vision.enrich")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}
