package com.pixflow.harness.state.testsupport;

import com.pixflow.infra.cache.counter.AtomicCounter;
import com.pixflow.infra.cache.key.CacheKey;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class FakeAtomicCounter implements AtomicCounter {
    private final Map<String, Long> values = new LinkedHashMap<>();
    private boolean failReads;
    private int getCalls;

    public void put(CacheKey key, long value) {
        values.put(key.value(), value);
    }

    public void failReads(boolean failReads) {
        this.failReads = failReads;
    }

    public int getCalls() {
        return getCalls;
    }

    @Override
    public long incrementBy(CacheKey key, long delta, Duration ttl) {
        long next = values.getOrDefault(key.value(), 0L) + delta;
        values.put(key.value(), next);
        return next;
    }

    @Override
    public long get(CacheKey key) {
        getCalls++;
        if (failReads) {
            throw new IllegalStateException("counter read failed");
        }
        return values.getOrDefault(key.value(), 0L);
    }

    @Override
    public void reset(CacheKey key) {
        values.remove(key.value());
    }
}
