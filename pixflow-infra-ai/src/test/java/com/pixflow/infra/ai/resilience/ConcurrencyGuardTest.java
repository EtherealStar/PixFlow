package com.pixflow.infra.ai.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.spi.GlobalConcurrencyLimiter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ConcurrencyGuardTest {

    @Test
    void usesNoopWhenLimiterMissing() {
        ConcurrencyGuard guard = new ConcurrencyGuard(null);
        guard.acquire(ModelRole.PRIMARY_CHAT, Duration.ZERO).close();
    }

    @Test
    void delegatesToLimiter() {
        AtomicBoolean acquired = new AtomicBoolean();
        ConcurrencyGuard guard = new ConcurrencyGuard((role, waitTime) -> () -> acquired.set(true));

        GlobalConcurrencyLimiter.Permit permit = guard.acquire(ModelRole.PRIMARY_CHAT, Duration.ZERO);
        permit.close();

        assertThat(acquired).isTrue();
    }
}
