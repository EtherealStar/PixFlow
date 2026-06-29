package com.pixflow.module.vision.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.vision.analyze.VisionTaskType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class VisionMetricsTest {

    @Test
    void recordsAnalyzeAndImageCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VisionMetrics metrics = new VisionMetrics(registry);

        metrics.recordAnalyze(VisionTaskType.DESCRIBE, false);
        metrics.recordAnalyze(VisionTaskType.DESCRIBE, true);
        metrics.recordImages("sent", 3);
        metrics.recordEnrich("filled");

        assertThat(registry.counter("pixflow.vision.analyze", "taskType", "DESCRIBE", "parse", "ok").count()).isEqualTo(1);
        assertThat(registry.counter("pixflow.vision.analyze", "taskType", "DESCRIBE", "parse", "degraded").count()).isEqualTo(1);
        assertThat(registry.counter("pixflow.vision.images", "stage", "sent").count()).isEqualTo(3);
        assertThat(registry.counter("pixflow.vision.enrich", "result", "filled").count()).isEqualTo(1);
    }
}
