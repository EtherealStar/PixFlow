package com.pixflow.infra.cache.semaphore;

import com.pixflow.infra.cache.config.CacheProperties;
import com.pixflow.infra.cache.error.CacheErrorCode;
import com.pixflow.infra.cache.error.CacheException;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.observability.CacheMetrics;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedissonDistributedSemaphore implements DistributedSemaphore {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedissonDistributedSemaphore.class);

    private final RedissonClient redissonClient;

    private final CacheProperties.Semaphore semaphoreProperties;

    private final CacheMetrics metrics;

    public RedissonDistributedSemaphore(
            RedissonClient redissonClient,
            CacheProperties.Semaphore semaphoreProperties,
            CacheMetrics metrics) {
        this.redissonClient = redissonClient;
        this.semaphoreProperties = semaphoreProperties;
        this.metrics = metrics;
    }

    @Override
    public Permit acquire(CacheKey key, int permits, Duration waitTime) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits 必须大于 0");
        }
        String api = apiName(key);
        CacheProperties.SemaphoreApi apiConfig = semaphoreProperties.resolve(api);
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(key.value());
        try {
            semaphore.trySetPermits(apiConfig.getPermits());
            List<String> permitIds = semaphore.tryAcquire(
                    permits,
                    waitTime.toMillis(),
                    apiConfig.getLeaseTime().toMillis(),
                    TimeUnit.MILLISECONDS);
            if (permitIds == null || permitIds.size() != permits) {
                releasePartial(semaphore, permitIds, api);
                metrics.recordSemaphore(api, "timeout");
                throw new CacheException(
                        CacheErrorCode.CACHE_SEMAPHORE_TIMEOUT,
                        "acquire",
                        key.namespace(),
                        "获取 Redis 信号量超时");
            }
            metrics.recordSemaphore(api, "acquired");
            return new RedissonPermit(semaphore, permitIds, api, metrics);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CacheException(
                    CacheErrorCode.CACHE_SEMAPHORE_FAILED,
                    "acquire",
                    key.namespace(),
                    "获取 Redis 信号量被中断",
                    ex);
        } catch (CacheException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            metrics.recordSemaphore(api, "error");
            throw new CacheException(
                    CacheErrorCode.CACHE_SEMAPHORE_FAILED,
                    "acquire",
                    key.namespace(),
                    "获取 Redis 信号量失败",
                    ex);
        }
    }

    private String apiName(CacheKey key) {
        String value = key.value();
        int index = value.lastIndexOf(':');
        return index >= 0 ? value.substring(index + 1) : key.namespace();
    }

    private void releasePartial(RPermitExpirableSemaphore semaphore, List<String> permitIds, String api) {
        if (permitIds == null || permitIds.isEmpty()) {
            return;
        }
        try {
            semaphore.release(permitIds);
            metrics.recordSemaphore(api, "partial_released");
        } catch (RuntimeException ex) {
            LOGGER.warn("redis semaphore partial release failed, api={}", api, ex);
            metrics.recordSemaphore(api, "partial_release_error");
        }
    }

    private static final class RedissonPermit implements Permit {
        private final RPermitExpirableSemaphore semaphore;

        private final List<String> permitIds;

        private final String api;

        private final CacheMetrics metrics;

        private final AtomicBoolean closed = new AtomicBoolean(false);

        private RedissonPermit(
                RPermitExpirableSemaphore semaphore,
                List<String> permitIds,
                String api,
                CacheMetrics metrics) {
            this.semaphore = semaphore;
            this.permitIds = List.copyOf(permitIds);
            this.api = api;
            this.metrics = metrics;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                semaphore.release(permitIds);
                metrics.recordSemaphore(api, "released");
            } catch (RuntimeException ex) {
                LOGGER.warn("redis semaphore release failed, api={}", api, ex);
                metrics.recordSemaphore(api, "release_error");
            }
        }
    }
}
