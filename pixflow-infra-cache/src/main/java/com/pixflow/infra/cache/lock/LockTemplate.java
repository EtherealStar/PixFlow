package com.pixflow.infra.cache.lock;

import com.pixflow.infra.cache.key.CacheKey;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface LockTemplate {
    <T> T runWithLock(CacheKey key, Duration waitTime, Supplier<T> action);

    void runWithLock(CacheKey key, Duration waitTime, Runnable action);

    boolean tryRunWithLock(CacheKey key, Duration waitTime, Consumer<LockGuard> action);
}
