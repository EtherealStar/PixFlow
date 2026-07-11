package com.pixflow.infra.cache.state;

import com.pixflow.infra.cache.key.CacheKey;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Redis Hash 形式的权威状态原语，批量读取用于获得同一时刻的业务快照。 */
public interface ExpiringHashStore {
    <T> Optional<T> get(CacheKey key, String field, Class<T> type);

    <T> void put(CacheKey key, String field, T value, Duration ttl);

    <T> Map<String, T> entries(CacheKey key, Class<T> type);

    Set<String> fields(CacheKey key);

    void deleteField(CacheKey key, String field);

    void expire(CacheKey key, Duration ttl);

    void delete(CacheKey key);
}
