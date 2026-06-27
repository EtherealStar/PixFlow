package com.pixflow.infra.ai.resilience;

import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.spi.GlobalConcurrencyLimiter;
import java.time.Duration;

/**
 * 对每次模型调用加全局并发许可。
 */
public final class ConcurrencyGuard {
    private final GlobalConcurrencyLimiter limiter;

    public ConcurrencyGuard(GlobalConcurrencyLimiter limiter) {
        this.limiter = limiter;
    }

    public GlobalConcurrencyLimiter.Permit acquire(ModelRole role, Duration waitTime) {
        if (limiter == null) {
            return NoopPermit.INSTANCE;
        }
        return limiter.acquire(role, waitTime);
    }

    private enum NoopPermit implements GlobalConcurrencyLimiter.Permit {
        INSTANCE;

        @Override
        public void close() {
            // no-op：未注入全局限流实现时，开发和单测环境保持可运行。
        }
    }
}
