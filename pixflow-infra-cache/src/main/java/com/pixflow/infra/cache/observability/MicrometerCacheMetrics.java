package com.pixflow.infra.cache.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;

public class MicrometerCacheMetrics implements CacheMetrics {
    private final MeterRegistry registry;

    public MicrometerCacheMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordCacheOperation(String operation, String namespace, String result) {
        registry.counter("pixflow.cache.op", "op", operation, "namespace", namespace, "result", result).increment();
    }

    @Override
    public void recordLock(String namespace, String result, Duration waitTime) {
        registry.counter("pixflow.cache.lock", "namespace", namespace, "result", result).increment();
        registry.timer("pixflow.cache.lock.wait", "namespace", namespace, "result", result).record(waitTime);
    }

    @Override
    public void recordSemaphore(String api, String result) {
        registry.counter("pixflow.cache.semaphore", "api", api, "result", result).increment();
    }

    @Override
    public void recordTokenBucket(String namespace, String result) {
        registry.counter("pixflow.cache.token_bucket", "namespace", namespace, "result", result).increment();
    }

}
