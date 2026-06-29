package com.pixflow.module.task.infra.lock;

import com.pixflow.infra.cache.lock.LockTemplate;
import com.pixflow.module.task.infra.cache.TaskCacheKeys;
import java.time.Duration;

public class TaskLockManager {
    private final LockTemplate lockTemplate;
    private final TaskCacheKeys keys;
    private final Duration waitTime;

    public TaskLockManager(LockTemplate lockTemplate, TaskCacheKeys keys, Duration waitTime) {
        this.lockTemplate = lockTemplate;
        this.keys = keys;
        this.waitTime = waitTime;
    }

    public boolean tryRunWithTaskLock(String taskId, Runnable action) {
        return lockTemplate.tryRunWithLock(keys.executionLockKey(taskId), waitTime, action);
    }
}
