package com.pixflow.harness.state.testsupport;

import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.store.CacheStore;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class FakeCacheStore implements CacheStore {
    private final Map<String, Object> values = new LinkedHashMap<>();
    private boolean failReads;
    private boolean failWrites;
    private boolean failDeletes;

    public void failReads(boolean failReads) {
        this.failReads = failReads;
    }

    public void failWrites(boolean failWrites) {
        this.failWrites = failWrites;
    }

    public void failDeletes(boolean failDeletes) {
        this.failDeletes = failDeletes;
    }

    @Override
    public <T> Optional<T> get(CacheKey key, Class<T> type) {
        if (failReads) {
            throw new IllegalStateException("cache read failed");
        }
        Object value = values.get(key.value());
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(type.cast(value));
    }

    @Override
    public <T> void put(CacheKey key, T value, Duration ttl) {
        if (failWrites) {
            throw new IllegalStateException("cache write failed");
        }
        values.put(key.value(), value);
    }

    @Override
    public <T> boolean putIfAbsent(CacheKey key, T value, Duration ttl) {
        if (failWrites) {
            throw new IllegalStateException("cache write failed");
        }
        return values.putIfAbsent(key.value(), value) == null;
    }

    @Override
    public boolean exists(CacheKey key) {
        return values.containsKey(key.value());
    }

    @Override
    public void delete(CacheKey key) {
        if (failDeletes) {
            throw new IllegalStateException("cache delete failed");
        }
        values.remove(key.value());
    }
}
