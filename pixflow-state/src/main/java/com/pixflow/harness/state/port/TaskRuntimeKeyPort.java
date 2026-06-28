package com.pixflow.harness.state.port;

import com.pixflow.infra.cache.key.CacheKey;

public interface TaskRuntimeKeyPort {
    CacheKey progressKey(String taskId);

    CacheKey cancelKey(String taskId);
}
