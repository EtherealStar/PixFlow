package com.pixflow.module.task.infra.cache;

import com.pixflow.harness.state.port.TaskRuntimeKeyPort;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.CacheNamespace;
import java.time.Duration;

public class TaskCacheKeys implements TaskRuntimeKeyPort {
    private final CacheNamespace namespace;
    private final Duration progressTtl;
    private final Duration cancelTtl;

    public TaskCacheKeys(CacheNamespace namespace, Duration progressTtl, Duration cancelTtl) {
        this.namespace = namespace;
        this.progressTtl = progressTtl;
        this.cancelTtl = cancelTtl;
    }

    @Override
    public CacheKey progressKey(String taskId) {
        return namespace.withDefaultTtl(progressTtl).key("task", "progress", taskId);
    }

    public CacheKey failedKey(String taskId) {
        return namespace.withDefaultTtl(progressTtl).key("task", "progress", taskId, "failed");
    }

    public CacheKey skippedKey(String taskId) {
        return namespace.withDefaultTtl(progressTtl).key("task", "progress", taskId, "skipped");
    }

    @Override
    public CacheKey cancelKey(String taskId) {
        return namespace.withDefaultTtl(cancelTtl).key("task", "cancel", taskId);
    }

    public CacheKey idempotencyKey(String key, Duration ttl) {
        return namespace.withDefaultTtl(ttl).key("task", "idempotency", Integer.toHexString(key.hashCode()));
    }

    public CacheKey heartbeatKey(String taskId, String workerId, Duration ttl) {
        return namespace.withDefaultTtl(ttl).key("task", "heartbeat", taskId, workerId);
    }

    public CacheKey executionLockKey(String taskId) {
        return namespace.key("task", "lock", "execution", taskId);
    }

    public CacheKey processSemaphoreKey() {
        return namespace.key("task", "semaphore", "process");
    }

    public CacheKey imagegenSemaphoreKey() {
        return namespace.key("task", "semaphore", "imagegen");
    }
}
