package com.pixflow.infra.cache.tokenbucket;

import com.pixflow.infra.cache.error.CacheErrorCode;
import com.pixflow.infra.cache.error.CacheException;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.observability.CacheMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

public final class RedisLuaTokenBucket implements DistributedTokenBucket {
    private static final long LUA_SAFE_INTEGER = 9_007_199_254_740_991L;

    private static final String TRY_CONSUME_SCRIPT = """
            local now = redis.call('TIME')
            local now_ms = now[1] * 1000 + math.floor(now[2] / 1000)
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local period_ms = tonumber(ARGV[3])
            local cost = tonumber(ARGV[4])
            local ttl_ms = tonumber(ARGV[5])
            local fingerprint = ARGV[6]
            local stored_fingerprint = redis.call('HGET', KEYS[1], 'policy')
            if stored_fingerprint and stored_fingerprint ~= fingerprint then
                return {-1, 0, 0}
            end

            -- 余额单位是“令牌 * 补充周期毫秒”，以整数保存分段补充产生的余数。
            local max_balance = capacity * period_ms
            local balance = tonumber(redis.call('HGET', KEYS[1], 'balance')) or max_balance
            local last_ms = tonumber(redis.call('HGET', KEYS[1], 'last_ms')) or now_ms
            local elapsed = math.max(0, now_ms - last_ms)
            balance = math.min(max_balance, balance + elapsed * refill)
            local cost_balance = cost * period_ms
            local allowed = 0
            local retry_ms = 0
            if balance >= cost_balance then
                balance = balance - cost_balance
                allowed = 1
            else
                retry_ms = math.ceil((cost_balance - balance) / refill)
            end

            redis.call('HSET', KEYS[1], 'policy', fingerprint, 'balance', balance, 'last_ms', now_ms)
            redis.call('PEXPIRE', KEYS[1], ttl_ms)
            return {allowed, math.floor(balance / period_ms), retry_ms}
            """;

    private final RedissonClient redissonClient;
    private final CacheMetrics metrics;

    public RedisLuaTokenBucket(RedissonClient redissonClient, CacheMetrics metrics) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public TokenBucketDecision tryConsume(CacheKey key, TokenBucketPolicy policy, long cost) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(policy, "policy");
        validateCost(policy, cost);
        long periodMillis = positiveMillis(policy.refillPeriod(), "refillPeriod");
        long idleTtlMillis = positiveMillis(policy.idleTtl(), "idleTtl");
        validateLuaRange(policy, cost, periodMillis);
        long ttlMillis = effectiveTtlMillis(policy, idleTtlMillis, periodMillis);
        validateElapsedRefillRange(policy, ttlMillis);
        String namespace = key.namespace();
        String fingerprint = policy.capacity() + ":" + policy.refillTokens() + ":" + periodMillis;
        try {
            List<?> result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    TRY_CONSUME_SCRIPT,
                    RScript.ReturnType.MULTI,
                    List.of(key.value()),
                    policy.capacity(),
                    policy.refillTokens(),
                    periodMillis,
                    cost,
                    ttlMillis,
                    fingerprint);
            long status = numberAt(result, 0);
            if (status == -1) {
                metrics.recordTokenBucket(namespace, "policy_conflict");
                throw new CacheException(
                        CacheErrorCode.CACHE_TOKEN_BUCKET_POLICY_CONFLICT,
                        "tryConsume",
                        namespace,
                        "同一令牌桶不能使用不同策略");
            }
            TokenBucketDecision decision = new TokenBucketDecision(
                    status == 1,
                    numberAt(result, 1),
                    Duration.ofMillis(numberAt(result, 2)));
            metrics.recordTokenBucket(namespace, decision.allowed() ? "allowed" : "rejected");
            return decision;
        } catch (CacheException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            metrics.recordTokenBucket(namespace, "redis_error");
            throw new CacheException(
                    CacheErrorCode.CACHE_TOKEN_BUCKET_FAILED,
                    "tryConsume",
                    namespace,
                    "Redis 令牌桶消费失败",
                    ex);
        }
    }

    private static long effectiveTtlMillis(TokenBucketPolicy policy, long idleTtlMillis, long periodMillis) {
        long fillMillis = ceilDivide(Math.multiplyExact(policy.capacity(), periodMillis), policy.refillTokens());
        return Math.max(idleTtlMillis, Math.multiplyExact(fillMillis, 2));
    }

    private static void validateCost(TokenBucketPolicy policy, long cost) {
        if (cost <= 0 || cost > policy.capacity()) {
            throw new IllegalArgumentException("cost 必须大于 0 且不能超过 capacity");
        }
    }

    private static void validateLuaRange(TokenBucketPolicy policy, long cost, long periodMillis) {
        try {
            long maxBalance = Math.multiplyExact(policy.capacity(), periodMillis);
            long costBalance = Math.multiplyExact(cost, periodMillis);
            if (maxBalance > LUA_SAFE_INTEGER || costBalance > LUA_SAFE_INTEGER
                    || policy.refillTokens() > LUA_SAFE_INTEGER) {
                throw new IllegalArgumentException("令牌桶策略超过 Redis Lua 安全整数范围");
            }
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("令牌桶策略数值溢出", ex);
        }
    }

    private static void validateElapsedRefillRange(TokenBucketPolicy policy, long ttlMillis) {
        try {
            if (Math.multiplyExact(ttlMillis, policy.refillTokens()) > LUA_SAFE_INTEGER) {
                throw new IllegalArgumentException("令牌桶 TTL 与补充速度超过 Redis Lua 安全整数范围");
            }
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("令牌桶 TTL 与补充速度数值溢出", ex);
        }
    }

    private static long positiveMillis(Duration duration, String name) {
        long millis;
        try {
            millis = duration.toMillis();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(name + " 超出毫秒范围", ex);
        }
        if (millis <= 0) {
            throw new IllegalArgumentException(name + " 必须至少为 1ms");
        }
        return millis;
    }

    private static long ceilDivide(long dividend, long divisor) {
        return dividend / divisor + (dividend % divisor == 0 ? 0 : 1);
    }

    private static long numberAt(List<?> values, int index) {
        Object value = values.get(index);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Lua 返回值格式错误");
        }
        return number.longValue();
    }
}
