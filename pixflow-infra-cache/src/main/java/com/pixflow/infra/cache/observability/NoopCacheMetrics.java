package com.pixflow.infra.cache.observability;

import java.time.Duration;

public class NoopCacheMetrics implements CacheMetrics {
    @Override
    public void recordCacheOperation(String operation, String namespace, String result) {
    }

    @Override
    public void recordLock(String namespace, String result, Duration waitTime) {
    }

    @Override
    public void recordSemaphore(String api, String result) {
    }

    @Override
    public void recordTokenBucket(String namespace, String result) {
    }

}
