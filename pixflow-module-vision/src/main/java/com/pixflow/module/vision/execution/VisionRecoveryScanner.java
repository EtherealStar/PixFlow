package com.pixflow.module.vision.execution;

import java.time.Clock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;

public final class VisionRecoveryScanner {
    private final VisionExecutionStore store;

    private final VisionWorkPublisher publisher;

    private final Clock clock;

    public VisionRecoveryScanner(VisionExecutionStore store, VisionWorkPublisher publisher, Clock clock) {
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
    }

    public int recover(Duration staleAfter, Duration pendingAfter, int limit) {
        int published = 0;
        for (Long id : store.expireStale(clock.instant().minus(staleAfter), clock.instant(), limit)) {
            publisher.publish(id);
            published++;
        }
        for (Long id : store.pendingBefore(clock.instant().minus(pendingAfter), limit)) {
            publisher.publish(id);
            published++;
        }
        return published;
    }

    @Scheduled(fixedDelayString = "${pixflow.vision.recovery.interval:PT30S}")
    public void recover() {
        recover(Duration.ofMinutes(2), Duration.ofSeconds(30), 100);
    }
}
