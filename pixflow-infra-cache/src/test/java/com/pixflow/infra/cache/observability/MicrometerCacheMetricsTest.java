package com.pixflow.infra.cache.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MicrometerCacheMetricsTest {

    @Test
    void recordsLowCardinalityCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerCacheMetrics metrics = new MicrometerCacheMetrics(registry);

        metrics.recordCacheOperation("get", "alpha", "hit");
        metrics.recordLock("alpha", "acquired", Duration.ofMillis(12));
        metrics.recordSemaphore("removebg", "acquired");

        assertThat(registry.counter("pixflow.cache.op", "op", "get", "namespace", "alpha", "result", "hit").count())
                .isEqualTo(1);
        assertThat(registry.counter("pixflow.cache.lock", "namespace", "alpha", "result", "acquired").count())
                .isEqualTo(1);
        assertThat(registry.counter("pixflow.cache.semaphore", "api", "removebg", "result", "acquired").count())
                .isEqualTo(1);
    }
}
