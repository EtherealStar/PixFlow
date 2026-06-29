package com.pixflow.module.task.infra.cache;

import com.pixflow.infra.cache.store.CacheStore;
import java.time.Duration;
import java.util.Optional;

public class TaskIdempotencyStore {
    private final CacheStore store;
    private final TaskCacheKeys keys;
    private final Duration ttl;

    public TaskIdempotencyStore(CacheStore store, TaskCacheKeys keys, Duration ttl) {
        this.store = store;
        this.keys = keys;
        this.ttl = ttl;
    }

    public Optional<String> get(String idempotencyKey) {
        return store.get(keys.idempotencyKey(idempotencyKey, ttl), String.class);
    }

    public boolean putIfAbsent(String idempotencyKey, String taskId) {
        return store.putIfAbsent(keys.idempotencyKey(idempotencyKey, ttl), taskId, ttl);
    }

    public void put(String idempotencyKey, String taskId) {
        store.put(keys.idempotencyKey(idempotencyKey, ttl), taskId, ttl);
    }
}
