package com.pixflow.app.ai;

import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.spi.GlobalConcurrencyLimiter;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.semaphore.DistributedSemaphore;
import java.time.Duration;
import java.util.Objects;

final class RedisAiConcurrencyLimiter implements GlobalConcurrencyLimiter {
    private final DistributedSemaphore semaphore;

    private final CacheNamespace namespace;

    RedisAiConcurrencyLimiter(DistributedSemaphore semaphore, CacheNamespace namespace) {
        this.semaphore = Objects.requireNonNull(semaphore, "semaphore");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    @Override
    public Permit acquire(ModelRole role, String provider, Duration waitTime) {
        DistributedSemaphore.Permit permit = semaphore.acquire(
                namespace.key("sem", "ai", provider, role.name().toLowerCase()),
                1,
                waitTime);
        return permit::close;
    }
}
