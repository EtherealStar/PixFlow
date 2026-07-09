package com.pixflow.module.conversation.api;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

final class SseHeartbeat {
    private final SseEmitter emitter;
    private final ScheduledExecutorService scheduler;
    private final Duration interval;
    private final Object sendLock;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();

    SseHeartbeat(SseEmitter emitter, ScheduledExecutorService scheduler, Duration interval, Object sendLock) {
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.interval = interval;
        this.sendLock = sendLock == null ? new Object() : sendLock;
    }

    void start() {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return;
        }
        emitter.onCompletion(this::stop);
        emitter.onTimeout(this::stop);
        emitter.onError(error -> stop());
        long periodMillis = Math.max(1L, interval.toMillis());
        ScheduledFuture<?> scheduled = scheduler.scheduleAtFixedRate(
                this::sendHeartbeat, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        if (!future.compareAndSet(null, scheduled) || stopped.get()) {
            scheduled.cancel(false);
        }
    }

    void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> current = future.getAndSet(null);
        if (current != null) {
            current.cancel(false);
        }
    }

    private void sendHeartbeat() {
        if (stopped.get()) {
            return;
        }
        try {
            synchronized (sendLock) {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            }
        } catch (IOException | IllegalStateException ex) {
            // heartbeat 失败通常表示客户端已断开，只取消保活任务，主回合按原路径收敛。
            stop();
        }
    }
}
