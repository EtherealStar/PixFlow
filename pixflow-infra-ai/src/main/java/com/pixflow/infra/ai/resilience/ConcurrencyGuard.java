package com.pixflow.infra.ai.resilience;

import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.spi.GlobalConcurrencyLimiter;
import java.time.Duration;
import java.util.Objects;

/**
 * 对每次模型调用加全局并发许可。
 */
public final class ConcurrencyGuard {
    private final GlobalConcurrencyLimiter limiter;

    public ConcurrencyGuard(GlobalConcurrencyLimiter limiter) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    public GlobalConcurrencyLimiter.Permit acquire(ModelRole role, String provider, Duration waitTime) {
        return limiter.acquire(role, provider, waitTime);
    }
}
