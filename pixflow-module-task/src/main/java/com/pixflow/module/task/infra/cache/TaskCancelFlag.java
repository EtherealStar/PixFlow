package com.pixflow.module.task.infra.cache;

import com.pixflow.infra.cache.store.CacheStore;
import java.time.Duration;

public class TaskCancelFlag {
  private final CacheStore store;

  private final TaskCacheKeys keys;

  private final Duration ttl;

  public TaskCancelFlag(CacheStore store, TaskCacheKeys keys, Duration ttl) {
    this.store = store;
    this.keys = keys;
    this.ttl = ttl;
  }

  public void requestCancel(String taskId, String reason) {
    store.put(keys.cancelKey(taskId), reason == null ? "cancelled" : reason, ttl);
  }

  public boolean isCancelRequested(String taskId) {
    return store.exists(keys.cancelKey(taskId));
  }
}
