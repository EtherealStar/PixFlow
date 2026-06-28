package com.pixflow.infra.cache.counter;

import com.pixflow.infra.cache.error.CacheErrorCode;
import com.pixflow.infra.cache.error.CacheException;
import com.pixflow.infra.cache.key.CacheKey;
import java.time.Duration;
import java.util.List;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

public class RedissonAtomicCounter implements AtomicCounter {
    private static final String INCREMENT_WITH_INITIAL_TTL = """
            local value = redis.call('INCRBY', KEYS[1], ARGV[1])
            if redis.call('TTL', KEYS[1]) == -1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return value
            """;

    private final RedissonClient redissonClient;

    public RedissonAtomicCounter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public long incrementBy(CacheKey key, long delta, Duration ttl) {
        try {
            Duration effectiveTtl = effectiveTtl(key, ttl);
            // 只在首次创建计数器时设置 TTL，避免频繁进度更新把 key 无限续命。
            Number value = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    INCREMENT_WITH_INITIAL_TTL,
                    RScript.ReturnType.INTEGER,
                    List.of(key.value()),
                    Long.toString(delta),
                    Long.toString(effectiveTtl.toMillis()));
            return value.longValue();
        } catch (RuntimeException ex) {
            throw new CacheException(CacheErrorCode.CACHE_COUNTER_FAILED, "increment", key.namespace(), "Redis 计数器自增失败", ex);
        }
    }

    @Override
    public long get(CacheKey key) {
        try {
            return redissonClient.getAtomicLong(key.value()).get();
        } catch (RuntimeException ex) {
            throw new CacheException(CacheErrorCode.CACHE_COUNTER_FAILED, "get", key.namespace(), "Redis 计数器读取失败", ex);
        }
    }

    @Override
    public void reset(CacheKey key) {
        try {
            redissonClient.getAtomicLong(key.value()).delete();
        } catch (RuntimeException ex) {
            throw new CacheException(CacheErrorCode.CACHE_COUNTER_FAILED, "reset", key.namespace(), "Redis 计数器重置失败", ex);
        }
    }

    private Duration effectiveTtl(CacheKey key, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return key.suggestedTtl();
        }
        return ttl;
    }
}
