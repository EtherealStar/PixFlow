package com.pixflow.module.conversation.api;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;

public final class SseTurnMetrics {
    private final MeterRegistry registry;
    private final AtomicInteger active = new AtomicInteger();

    public SseTurnMetrics(MeterRegistry registry) {
        this.registry = registry;
        if (registry != null) {
            registry.gauge("pixflow.conversation.sse.active", active);
        }
    }

    public void sessionStarted() {
        active.incrementAndGet();
    }

    public void sessionEnded(SseTerminationReason reason) {
        active.updateAndGet(value -> Math.max(0, value - 1));
        if (registry != null) {
            registry.counter("pixflow.conversation.sse.terminated", "reason", reason.name()).increment();
        }
    }

    public void lateWrite() {
        if (registry != null) {
            registry.counter("pixflow.conversation.sse.late_write").increment();
        }
    }

    public void executorRejected() {
        if (registry != null) {
            registry.counter("pixflow.conversation.sse.executor_rejected").increment();
        }
    }
}
