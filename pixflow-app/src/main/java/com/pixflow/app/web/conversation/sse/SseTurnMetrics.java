package com.pixflow.app.web.conversation.sse;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public final class SseTurnMetrics {
    private final MeterRegistry registry;

    private final AtomicInteger active = new AtomicInteger();

    public SseTurnMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        registry.gauge("pixflow.conversation.sse.active", active);
    }

    public void sessionStarted() {
        active.incrementAndGet();
    }

    public void sessionEnded(SseTerminationReason reason) {
        active.updateAndGet(value -> Math.max(0, value - 1));
        registry.counter("pixflow.conversation.sse.terminated", "reason", reason.name()).increment();
    }

    public void lateWrite() {
        registry.counter("pixflow.conversation.sse.late_write").increment();
    }

    public void executorRejected() {
        registry.counter("pixflow.conversation.sse.executor_rejected").increment();
    }
}
