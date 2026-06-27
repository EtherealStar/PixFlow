package com.pixflow.infra.cache.counter;

import com.pixflow.infra.cache.key.CacheKey;
import java.time.Duration;

public interface AtomicCounter {
    long incrementBy(CacheKey key, long delta, Duration ttl);

    long get(CacheKey key);

    void reset(CacheKey key);
}
