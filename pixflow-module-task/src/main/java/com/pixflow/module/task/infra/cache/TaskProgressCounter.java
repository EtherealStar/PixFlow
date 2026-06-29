package com.pixflow.module.task.infra.cache;

import com.pixflow.infra.cache.counter.AtomicCounter;
import com.pixflow.module.task.domain.progress.ProgressSnapshot;
import java.time.Duration;

public class TaskProgressCounter {
    private final AtomicCounter counter;
    private final TaskCacheKeys keys;
    private final Duration ttl;

    public TaskProgressCounter(AtomicCounter counter, TaskCacheKeys keys, Duration ttl) {
        this.counter = counter;
        this.keys = keys;
        this.ttl = ttl;
    }

    public long incrementDone(String taskId) {
        return counter.incrementBy(keys.progressKey(taskId), 1, ttl);
    }

    public long incrementFailed(String taskId) {
        return counter.incrementBy(keys.failedKey(taskId), 1, ttl);
    }

    public long incrementSkipped(String taskId) {
        return counter.incrementBy(keys.skippedKey(taskId), 1, ttl);
    }

    public ProgressSnapshot snapshot(String taskId, int total) {
        return new ProgressSnapshot(total,
                Math.toIntExact(counter.get(keys.progressKey(taskId))),
                Math.toIntExact(counter.get(keys.failedKey(taskId))),
                Math.toIntExact(counter.get(keys.skippedKey(taskId))));
    }

    public void reset(String taskId) {
        counter.reset(keys.progressKey(taskId));
        counter.reset(keys.failedKey(taskId));
        counter.reset(keys.skippedKey(taskId));
    }
}
