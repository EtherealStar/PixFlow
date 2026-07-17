package com.pixflow.infra.ai.observability;

import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.model.ModelCapability;
import com.pixflow.infra.ai.model.ModelRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ai 模块的 Micrometer 指标封装。
 */
public final class AiMetrics {
    public enum QuotaResult {
        ALLOWED,
        REJECTED,
        ERROR
    }

    private final MeterRegistry meterRegistry;

    private final AtomicInteger concurrency = new AtomicInteger();

    public AiMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        Gauge.builder("pixflow.ai.concurrency", concurrency, AtomicInteger::get).register(meterRegistry);
    }

    public Timer.Sample startCall() {
        return Timer.start(meterRegistry);
    }

    public void recordCall(
            Timer.Sample sample,
            ModelRole role,
            String provider,
            ModelCapability capability,
            boolean ok) {
        sample.stop(Timer.builder("pixflow.ai.call")
                .tag("role", role.name())
                .tag("provider", provider)
                .tag("capability", capability.name())
                .tag("result", ok ? "ok" : "error")
                .register(meterRegistry));
    }

    public void incrementTokens(ModelRole role, String type, long amount) {
        if (amount <= 0) {
            return;
        }
        Counter.builder("pixflow.ai.tokens")
                .tag("role", role.name())
                .tag("type", type)
                .register(meterRegistry)
                .increment(amount);
    }

    public void incrementRetry(ModelRole role, AiErrorCode code) {
        Counter.builder("pixflow.ai.retry")
                .tag("role", role.name())
                .tag("reason", code.code())
                .register(meterRegistry)
                .increment();
    }

    public void recordQuota(ModelRole role, String provider, QuotaResult result) {
        Counter.builder("pixflow.ai.quota")
                .tag("role", role.name())
                .tag("provider", provider)
                .tag("result", result.name().toLowerCase(java.util.Locale.ROOT))
                .register(meterRegistry)
                .increment();
    }

    public void incrementConcurrency() {
        concurrency.incrementAndGet();
    }

    public void decrementConcurrency() {
        concurrency.decrementAndGet();
    }
}
