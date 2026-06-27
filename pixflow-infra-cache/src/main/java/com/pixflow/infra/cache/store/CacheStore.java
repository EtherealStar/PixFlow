package com.pixflow.infra.cache.store;

import com.pixflow.infra.cache.key.CacheKey;
import java.time.Duration;
import java.util.Optional;

public interface CacheStore {
    <T> Optional<T> get(CacheKey key, Class<T> type);

    <T> void put(CacheKey key, T value, Duration ttl);

    <T> boolean putIfAbsent(CacheKey key, T value, Duration ttl);

    boolean exists(CacheKey key);

    void delete(CacheKey key);
}
