package com.pixflow.infra.cache.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.observability.CacheMetrics;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedissonCacheStore implements CacheStore {
    private static final Logger log = LoggerFactory.getLogger(RedissonCacheStore.class);

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final CacheMetrics metrics;

    public RedissonCacheStore(RedissonClient redissonClient, ObjectMapper objectMapper, CacheMetrics metrics) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    public <T> Optional<T> get(CacheKey key, Class<T> type) {
        try {
            Object value = redissonClient.<Object>getBucket(key.value()).get();
            if (value == null) {
                metrics.recordCacheOperation("get", key.namespace(), "miss");
                return Optional.empty();
            }
            T converted = type.isInstance(value) ? type.cast(value) : objectMapper.convertValue(value, type);
            metrics.recordCacheOperation("get", key.namespace(), "hit");
            return Optional.of(converted);
        } catch (RuntimeException ex) {
            log.warn("cache get degraded to miss, namespace={}", key.namespace(), ex);
            metrics.recordCacheOperation("get", key.namespace(), "error");
            return Optional.empty();
        }
    }

    @Override
    public <T> void put(CacheKey key, T value, Duration ttl) {
        try {
            Duration effectiveTtl = effectiveTtl(key, ttl);
            redissonClient.<Object>getBucket(key.value()).set(value, effectiveTtl.toMillis(), TimeUnit.MILLISECONDS);
            metrics.recordCacheOperation("put", key.namespace(), "ok");
        } catch (RuntimeException ex) {
            log.warn("cache put degraded, namespace={}", key.namespace(), ex);
            metrics.recordCacheOperation("put", key.namespace(), "error");
        }
    }

    @Override
    public <T> boolean putIfAbsent(CacheKey key, T value, Duration ttl) {
        try {
            Duration effectiveTtl = effectiveTtl(key, ttl);
            RBucket<Object> bucket = redissonClient.getBucket(key.value());
            boolean stored = bucket.trySet(value, effectiveTtl.toMillis(), TimeUnit.MILLISECONDS);
            metrics.recordCacheOperation("put_if_absent", key.namespace(), stored ? "stored" : "exists");
            return stored;
        } catch (RuntimeException ex) {
            log.warn("cache putIfAbsent degraded, namespace={}", key.namespace(), ex);
            metrics.recordCacheOperation("put_if_absent", key.namespace(), "error");
            return false;
        }
    }

    @Override
    public boolean exists(CacheKey key) {
        try {
            boolean exists = redissonClient.getBucket(key.value()).isExists();
            metrics.recordCacheOperation("exists", key.namespace(), exists ? "hit" : "miss");
            return exists;
        } catch (RuntimeException ex) {
            log.warn("cache exists degraded to false, namespace={}", key.namespace(), ex);
            metrics.recordCacheOperation("exists", key.namespace(), "error");
            return false;
        }
    }

    @Override
    public void delete(CacheKey key) {
        try {
            redissonClient.getBucket(key.value()).delete();
            metrics.recordCacheOperation("delete", key.namespace(), "ok");
        } catch (RuntimeException ex) {
            log.warn("cache delete degraded, namespace={}", key.namespace(), ex);
            metrics.recordCacheOperation("delete", key.namespace(), "error");
        }
    }

    private Duration effectiveTtl(CacheKey key, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return key.suggestedTtl();
        }
        return ttl;
    }
}
