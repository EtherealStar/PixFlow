package com.pixflow.module.vision.execution;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.scheduling.TaskScheduler;

/** provider 阻塞期间持续刷新 MySQL owner lease。 */
public final class VisionHeartbeat {
    private static final Duration INTERVAL = Duration.ofSeconds(10);

    private final VisionExecutionStore store;

    private final TaskScheduler scheduler;

    private final Clock clock;

    public VisionHeartbeat(VisionExecutionStore store, TaskScheduler scheduler, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public <T> T whileCalling(VisionWorkItem item, Supplier<T> call) {
        AtomicBoolean stale = new AtomicBoolean(!beat(item));
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> stale.compareAndSet(false, !beat(item)), INTERVAL);
        try {
            if (stale.get()) {
                throw new IllegalStateException("visual analysis owner is stale");
            }
            T result = call.get();
            if (stale.get() || !beat(item)) {
                throw new IllegalStateException("visual analysis owner is stale");
            }
            return result;
        } finally {
            future.cancel(false);
        }
    }

    private boolean beat(VisionWorkItem item) {
        return store.heartbeat(item.id(), item.analysisGeneration(), item.runEpoch(), clock.instant());
    }
}
