package com.pixflow.infra.cache.observability;

import java.time.Duration;

/**
 * cache 模块指标接口。tag 只允许低基数字段，不能传完整 Redis key。
 */
public interface CacheMetrics {
    void recordCacheOperation(String operation, String namespace, String result);

    void recordLock(String namespace, String result, Duration waitTime);

    void recordSemaphore(String api, String result);

    void recordConfirmationToken(String operation, String result);
}
