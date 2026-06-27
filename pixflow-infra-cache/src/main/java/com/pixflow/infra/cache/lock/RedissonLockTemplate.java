package com.pixflow.infra.cache.lock;

import com.pixflow.infra.cache.error.CacheErrorCode;
import com.pixflow.infra.cache.error.CacheException;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.observability.CacheMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

public class RedissonLockTemplate implements LockTemplate {
    private final RedissonClient redissonClient;
    private final CacheMetrics metrics;

    public RedissonLockTemplate(RedissonClient redissonClient, CacheMetrics metrics) {
        this.redissonClient = redissonClient;
        this.metrics = metrics;
    }

    @Override
    public <T> T runWithLock(CacheKey key, Duration waitTime, Supplier<T> action) {
        Instant start = Instant.now();
        RLock lock = redissonClient.getLock(key.value());
        boolean acquired;
        try {
            // 不传 leaseTime，保留 Redisson watchdog 自动续期语义，适配长临界区。
            acquired = lock.tryLock(waitTime.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CacheException(CacheErrorCode.CACHE_REDIS_UNAVAILABLE, "lock", key.namespace(), "获取 Redis 锁被中断", ex);
        } catch (RuntimeException ex) {
            metrics.recordLock(key.namespace(), "error", Duration.between(start, Instant.now()));
            throw new CacheException(CacheErrorCode.CACHE_REDIS_UNAVAILABLE, "lock", key.namespace(), "获取 Redis 锁失败", ex);
        }
        if (!acquired) {
            metrics.recordLock(key.namespace(), "timeout", Duration.between(start, Instant.now()));
            throw new CacheException(CacheErrorCode.CACHE_LOCK_ACQUIRE_TIMEOUT, "lock", key.namespace(), "获取 Redis 锁超时");
        }
        metrics.recordLock(key.namespace(), "acquired", Duration.between(start, Instant.now()));
        Throwable actionFailure = null;
        try {
            return action.get();
        } catch (RuntimeException | Error ex) {
            actionFailure = ex;
            throw ex;
        } finally {
            release(key, lock, actionFailure);
        }
    }

    @Override
    public void runWithLock(CacheKey key, Duration waitTime, Runnable action) {
        runWithLock(key, waitTime, () -> {
            action.run();
            return null;
        });
    }

    @Override
    public boolean tryRunWithLock(CacheKey key, Duration waitTime, Runnable action) {
        Instant start = Instant.now();
        RLock lock = redissonClient.getLock(key.value());
        boolean acquired;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CacheException(CacheErrorCode.CACHE_REDIS_UNAVAILABLE, "try_lock", key.namespace(), "获取 Redis 锁被中断", ex);
        } catch (RuntimeException ex) {
            metrics.recordLock(key.namespace(), "error", Duration.between(start, Instant.now()));
            throw new CacheException(CacheErrorCode.CACHE_REDIS_UNAVAILABLE, "try_lock", key.namespace(), "获取 Redis 锁失败", ex);
        }
        if (!acquired) {
            metrics.recordLock(key.namespace(), "skipped", Duration.between(start, Instant.now()));
            return false;
        }
        metrics.recordLock(key.namespace(), "acquired", Duration.between(start, Instant.now()));
        Throwable actionFailure = null;
        try {
            action.run();
            return true;
        } catch (RuntimeException | Error ex) {
            actionFailure = ex;
            throw ex;
        } finally {
            release(key, lock, actionFailure);
        }
    }

    private void release(CacheKey key, RLock lock, Throwable actionFailure) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException ex) {
            CacheException cacheException = new CacheException(
                    CacheErrorCode.CACHE_LOCK_RELEASE_FAILED,
                    "unlock",
                    key.namespace(),
                    "释放 Redis 锁失败",
                    ex);
            if (actionFailure != null) {
                actionFailure.addSuppressed(cacheException);
                return;
            }
            throw cacheException;
        }
    }
}
