package com.pixflow.infra.cache.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.cache.error.CacheErrorCode;
import com.pixflow.infra.cache.error.CacheException;
import com.pixflow.infra.cache.key.CacheKey;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

public final class RedissonExpiringStateStore implements ExpiringStateStore {
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    public RedissonExpiringStateStore(RedissonClient redissonClient, ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> Optional<T> get(CacheKey key, Class<T> type) {
        try {
            String json = bucket(key).get();
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, type));
        } catch (JsonProcessingException ex) {
            throw serialization("state_get", key, ex);
        } catch (RuntimeException ex) {
            throw unavailable("state_get", key, ex);
        }
    }

    @Override
    public <T> void put(CacheKey key, T value, Duration ttl) {
        requireTtl(ttl);
        try {
            bucket(key).set(objectMapper.writeValueAsString(value), ttl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException ex) {
            throw serialization("state_put", key, ex);
        } catch (RuntimeException ex) {
            throw unavailable("state_put", key, ex);
        }
    }

    @Override
    public <T> boolean putIfAbsent(CacheKey key, T value, Duration ttl) {
        requireTtl(ttl);
        try {
            return bucket(key).trySet(objectMapper.writeValueAsString(value), ttl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException ex) {
            throw serialization("state_put_if_absent", key, ex);
        } catch (RuntimeException ex) {
            throw unavailable("state_put_if_absent", key, ex);
        }
    }

    @Override
    public void expire(CacheKey key, Duration ttl) {
        requireTtl(ttl);
        try {
            bucket(key).expire(ttl);
        } catch (RuntimeException ex) {
            throw unavailable("state_expire", key, ex);
        }
    }

    @Override
    public void delete(CacheKey key) {
        try {
            bucket(key).delete();
        } catch (RuntimeException ex) {
            throw unavailable("state_delete", key, ex);
        }
    }

    private RBucket<String> bucket(CacheKey key) {
        return redissonClient.getBucket(key.value(), StringCodec.INSTANCE);
    }

    private static void requireTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("ttl 必须为正数");
    }

    private static CacheException serialization(String operation, CacheKey key, Exception ex) {
        return new CacheException(CacheErrorCode.CACHE_SERIALIZATION_FAILED, operation, key.namespace(), "权威状态序列化失败", ex);
    }

    private static CacheException unavailable(String operation, CacheKey key, RuntimeException ex) {
        if (ex instanceof CacheException cacheException) return cacheException;
        return new CacheException(CacheErrorCode.CACHE_REDIS_UNAVAILABLE, operation, key.namespace(), "Redis 权威状态操作失败", ex);
    }
}
