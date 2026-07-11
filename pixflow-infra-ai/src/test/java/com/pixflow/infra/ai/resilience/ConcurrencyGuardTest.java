package com.pixflow.infra.ai.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.spi.GlobalConcurrencyLimiter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ConcurrencyGuardTest {

    @Test
    void rejectsMissingLimiter() {
        assertThatNullPointerException().isThrownBy(() -> new ConcurrencyGuard(null));
    }

    @Test
    void delegatesToLimiter() {
        AtomicBoolean acquired = new AtomicBoolean();
        ConcurrencyGuard guard = new ConcurrencyGuard((role, provider, waitTime) -> () -> acquired.set(true));

        GlobalConcurrencyLimiter.Permit permit = guard.acquire(ModelRole.PRIMARY_CHAT, "test", Duration.ZERO);
        permit.close();

        assertThat(acquired).isTrue();
    }
}
