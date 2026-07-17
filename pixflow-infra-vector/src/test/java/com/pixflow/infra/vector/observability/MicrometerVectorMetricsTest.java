package com.pixflow.infra.vector.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MicrometerVectorMetricsTest {
    @Test
    void recordsLowCardinalityOperationAndResultTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerVectorMetrics metrics = new MicrometerVectorMetrics(registry);

        metrics.recordOperation("health", "degraded", Duration.ofMillis(10));

        assertThat(registry.get("pixflow.vector.op")
                .tags("operation", "health", "result", "degraded")
                .timer()
                .count()).isEqualTo(1);
        assertThat(registry.find("pixflow.vector.op").tag("op", "health").timer()).isNull();
    }
}
