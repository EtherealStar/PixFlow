package com.pixflow.infra.thirdparty.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public final class ThirdPartyMetrics {
    public enum QuotaResult {
        ALLOWED,
        REJECTED,
        ERROR
    }

    private final MeterRegistry registry;

    private final AtomicInteger inFlight = new AtomicInteger();

    public ThirdPartyMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Gauge.builder("pixflow.thirdparty.inflight", inFlight, AtomicInteger::get).register(registry);
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void recordCall(Timer.Sample sample, String api, String provider, boolean ok) {
        sample.stop(Timer.builder("pixflow.thirdparty.call")
                .tag("api", api)
                .tag("provider", provider)
                .tag("result", ok ? "ok" : "error")
                .register(registry));
    }

    public void recordRetry(String api, String provider) {
        Counter.builder("pixflow.thirdparty.retry")
                .tag("api", api)
                .tag("provider", provider)
                .register(registry)
                .increment();
    }

    public void recordQuota(String provider, String api, QuotaResult result) {
        Counter.builder("pixflow.thirdparty.quota")
                .tag("provider", provider)
                .tag("api", api)
                .tag("result", result.name().toLowerCase(java.util.Locale.ROOT))
                .register(registry)
                .increment();
    }

    public void recordResponseBytes(String api, String provider, long bytes) {
        registry.summary("pixflow.thirdparty.response.bytes", "api", api, "provider", provider).record(bytes);
    }

    public void incrementInFlight() {
        inFlight.incrementAndGet();
    }

    public void decrementInFlight() {
        inFlight.decrementAndGet();
    }
}
