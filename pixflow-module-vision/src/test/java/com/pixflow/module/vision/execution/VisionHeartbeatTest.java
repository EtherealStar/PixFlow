package com.pixflow.module.vision.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

class VisionHeartbeatTest {
    private static final Instant NOW = Instant.parse("2026-07-19T10:00:00Z");

    @Test
    void returnsProviderResultWhileOwnerRemainsCurrent() {
        VisionExecutionStore store = mock(VisionExecutionStore.class);
        when(store.heartbeat(9L, 3L, 2L, NOW)).thenReturn(true);
        VisionHeartbeat heartbeat = heartbeat(store);

        assertThat(heartbeat.whileCalling(item(), () -> "ok")).isEqualTo("ok");
    }

    @Test
    void rejectsCallBeforeProviderWhenOwnerIsAlreadyStale() {
        VisionExecutionStore store = mock(VisionExecutionStore.class);
        when(store.heartbeat(9L, 3L, 2L, NOW)).thenReturn(false);
        VisionHeartbeat heartbeat = heartbeat(store);

        assertThatThrownBy(() -> heartbeat.whileCalling(item(), () -> "never"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
    }

    private static VisionHeartbeat heartbeat(VisionExecutionStore store) {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(java.time.Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));
        return new VisionHeartbeat(store, scheduler, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static VisionWorkItem item() {
        return new VisionWorkItem(9L, 7L, "SKU-1", "SKU", 0L, "hash", "RUNNING",
                3L, 2L, 0L, 0, 0);
    }
}
