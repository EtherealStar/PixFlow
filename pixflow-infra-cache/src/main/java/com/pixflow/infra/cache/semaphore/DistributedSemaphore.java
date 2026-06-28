package com.pixflow.infra.cache.semaphore;

import com.pixflow.infra.cache.key.CacheKey;
import java.time.Duration;

public interface DistributedSemaphore {
    /**
     * Acquires a distributed semaphore permit bundle. Implementations must preserve
     * the interrupted flag and wrap interruption in a cache-layer runtime exception.
     */
    Permit acquire(CacheKey key, int permits, Duration waitTime);

    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
