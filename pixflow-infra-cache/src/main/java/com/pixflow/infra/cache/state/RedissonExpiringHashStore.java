package com.pixflow.infra.cache.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.cache.error.CacheErrorCode;
import com.pixflow.infra.cache.error.CacheException;
import com.pixflow.infra.cache.key.CacheKey;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

public final class RedissonExpiringHashStore implements ExpiringHashStore {
    private final RedissonClient redissonClient;

    private final ObjectMapper objectMapper;

    public RedissonExpiringHashStore(RedissonClient redissonClient, ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> Optional<T> get(CacheKey key, String field, Class<T> type) {
        try {
            String json = map(key).get(field);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, type));
        } catch (JsonProcessingException ex) {
            throw serialization("hash_get", key, ex);
        } catch (RuntimeException ex) {
            throw unavailable("hash_get", key, ex);
        }
    }

    @Override
    public <T> void put(CacheKey key, String field, T value, Duration ttl) {
        requireTtl(ttl);
        try {
            RMap<String, String> map = map(key);
            map.put(field, objectMapper.writeValueAsString(value));
            map.expire(ttl);
        } catch (JsonProcessingException ex) {
            throw serialization("hash_put", key, ex);
        } catch (RuntimeException ex) {
            throw unavailable("hash_put", key, ex);
        }
    }

    @Override
    public <T> Map<String, T> entries(CacheKey key, Class<T> type) {
        try {
            Map<String, T> result = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : map(key).readAllMap().entrySet()) {
                result.put(entry.getKey(), objectMapper.readValue(entry.getValue(), type));
            }
            return Map.copyOf(result);
        } catch (JsonProcessingException ex) {
            throw serialization("hash_entries", key, ex);
        } catch (RuntimeException ex) {
            throw unavailable("hash_entries", key, ex);
        }
    }

    @Override
    public Set<String> fields(CacheKey key) {
        try {
            return Set.copyOf(map(key).readAllKeySet());
        } catch (RuntimeException ex) {
            throw unavailable("hash_fields", key, ex);
        }
    }

    @Override
    public void deleteField(CacheKey key, String field) {
        try {
            map(key).fastRemove(field);
        } catch (RuntimeException ex) {
            throw unavailable("hash_delete_field", key, ex);
        }
    }

    @Override
    public void expire(CacheKey key, Duration ttl) {
        requireTtl(ttl);
        try {
            map(key).expire(ttl);
        } catch (RuntimeException ex) {
            throw unavailable("hash_expire", key, ex);
        }
    }

    @Override
    public void delete(CacheKey key) {
        try {
            map(key).delete();
        } catch (RuntimeException ex) {
            throw unavailable("hash_delete", key, ex);
        }
    }

    private RMap<String, String> map(CacheKey key) {
        return redissonClient.getMap(key.value(), StringCodec.INSTANCE);
    }

    private static void requireTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl 必须为正数");
        }
    }

    private static CacheException serialization(String operation, CacheKey key, Exception ex) {
        return new CacheException(
                CacheErrorCode.CACHE_SERIALIZATION_FAILED,
                operation,
                key.namespace(),
                "Hash 状态序列化失败",
                ex);
    }

    private static CacheException unavailable(String operation, CacheKey key, RuntimeException ex) {
        if (ex instanceof CacheException cacheException) {
            return cacheException;
        }
        return new CacheException(
                CacheErrorCode.CACHE_REDIS_UNAVAILABLE,
                operation,
                key.namespace(),
                "Redis Hash 状态操作失败",
                ex);
    }
}
