package com.pixflow.infra.cache.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

/**
 * cache 模块内部错误码，统一归为依赖类错误。
 */
public enum CacheErrorCode implements ErrorCode {
    CACHE_REDIS_UNAVAILABLE,
    CACHE_SERIALIZATION_FAILED,
    CACHE_LOCK_ACQUIRE_TIMEOUT,
    CACHE_LOCK_RELEASE_FAILED,
    CACHE_COUNTER_FAILED,
    CACHE_SEMAPHORE_TIMEOUT,
    CACHE_SEMAPHORE_FAILED,
    CACHE_CONFIRMATION_TOKEN_FAILED;

    @Override
    public String code() {
        return name();
    }

    @Override
    public ErrorCategory category() {
        return ErrorCategory.DEPENDENCY;
    }
}
