package com.pixflow.infra.cache.state;

import com.pixflow.infra.cache.key.CacheKey;
import java.time.Duration;
import java.util.Optional;

/** 带过期时间的权威状态原语；依赖失败时必须抛异常，不能降级为未命中。 */
public interface ExpiringStateStore {
    <T> Optional<T> get(CacheKey key, Class<T> type);

    <T> void put(CacheKey key, T value, Duration ttl);

    <T> boolean putIfAbsent(CacheKey key, T value, Duration ttl);

    void expire(CacheKey key, Duration ttl);

    void delete(CacheKey key);
}
