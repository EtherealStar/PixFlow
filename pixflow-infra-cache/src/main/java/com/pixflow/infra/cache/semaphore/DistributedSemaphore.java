package com.pixflow.infra.cache.semaphore;

import com.pixflow.infra.cache.key.CacheKey;
import java.time.Duration;

public interface DistributedSemaphore {
    Permit acquire(CacheKey key, int permits, Duration waitTime);

    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
