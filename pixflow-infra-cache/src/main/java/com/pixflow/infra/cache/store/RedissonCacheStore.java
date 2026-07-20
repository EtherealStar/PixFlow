package com.pixflow.infra.cache.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.observability.CacheMetrics;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedissonCacheStore implements CacheStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedissonCacheStore.class);

    private final RedissonClient redissonClient;

    private final JsonCacheSerializer serializer;

    private final CacheMetrics metrics;

    public RedissonCacheStore(RedissonClient redissonClient, ObjectMapper objectMapper, CacheMetrics metrics) {
        this.redissonClient = redissonClient;
        this.serializer = new JsonCacheSerializer(objectMapper);
        this.metrics = metrics;
    }

    @Override
    public <T> Optional<T> get(CacheKey key, Class<T> type) {
        try {
            String json = redissonClient.<String>getBucket(key.value(), StringCodec.INSTANCE).get();
            if (json == null || json.isBlank()) {
                metrics.recordCacheOperation("get", key.namespace(), "miss");
                return Optional.empty();
            }
            T converted = serializer.deserialize(json, type);
            metrics.recordCacheOperation("get", key.namespace(), "hit");
            return Optional.of(converted);
        } catch (RuntimeException ex) {
            LOGGER.warn("cache get degraded to miss, namespace={}, key={}, error={}",
            key.namespace(), key.value(), ex.toString(), ex);
            metrics.recordCacheOperation("get", key.namespace(), "error");
            return Optional.empty();
        }
    }

    @Override
    public <T> Optional<T> consume(CacheKey key, Class<T> type) {
        try {
            String json = redissonClient.<String>getBucket(key.value(), StringCodec.INSTANCE).getAndDelete();
            if (json == null || json.isBlank()) {
                metrics.recordCacheOperation("consume", key.namespace(), "miss");
                return Optional.empty();
            }
            T converted = serializer.deserialize(json, type);
            metrics.recordCacheOperation("consume", key.namespace(), "hit");
            return Optional.of(converted);
        } catch (RuntimeException ex) {
            LOGGER.warn("cache consume degraded to miss, namespace={}, key={}, error={}",
                    key.namespace(), key.value(), ex.toString(), ex);
            metrics.recordCacheOperation("consume", key.namespace(), "error");
            return Optional.empty();
        }
    }

    @Override
    public <T> void put(CacheKey key, T value, Duration ttl) {
        try {
            Duration effectiveTtl = effectiveTtl(key, ttl);
            String json = serializer.serialize(value);
            redissonClient.<String>getBucket(key.value(), StringCodec.INSTANCE)
                    .set(json, effectiveTtl.toMillis(), TimeUnit.MILLISECONDS);
            metrics.recordCacheOperation("put", key.namespace(), "ok");
        } catch (RuntimeException ex) {
            LOGGER.warn("cache put degraded, namespace={}, error={}", key.namespace(), ex.getClass().getSimpleName());
            metrics.recordCacheOperation("put", key.namespace(), "error");
        }
    }

    @Override
    public <T> boolean putIfAbsent(CacheKey key, T value, Duration ttl) {
        try {
            Duration effectiveTtl = effectiveTtl(key, ttl);
            String json = serializer.serialize(value);
            RBucket<String> bucket = redissonClient.getBucket(key.value(), StringCodec.INSTANCE);
            boolean stored = bucket.trySet(json, effectiveTtl.toMillis(), TimeUnit.MILLISECONDS);
            metrics.recordCacheOperation("put_if_absent", key.namespace(), stored ? "stored" : "exists");
            return stored;
        } catch (RuntimeException ex) {
            LOGGER.warn("cache putIfAbsent degraded, namespace={}, error={}",
                    key.namespace(), ex.getClass().getSimpleName());
            metrics.recordCacheOperation("put_if_absent", key.namespace(), "error");
            return false;
        }
    }

    @Override
    public boolean exists(CacheKey key) {
        try {
            boolean exists = redissonClient.getBucket(key.value(), StringCodec.INSTANCE).isExists();
            metrics.recordCacheOperation("exists", key.namespace(), exists ? "hit" : "miss");
            return exists;
        } catch (RuntimeException ex) {
            LOGGER.warn("cache exists degraded to false, namespace={}, error={}",
                    key.namespace(), ex.getClass().getSimpleName());
            metrics.recordCacheOperation("exists", key.namespace(), "error");
            return false;
        }
    }

    @Override
    public void delete(CacheKey key) {
        try {
            redissonClient.getBucket(key.value(), StringCodec.INSTANCE).delete();
            metrics.recordCacheOperation("delete", key.namespace(), "ok");
        } catch (RuntimeException ex) {
            LOGGER.warn("cache delete degraded, namespace={}, error={}",
                    key.namespace(), ex.getClass().getSimpleName());
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
