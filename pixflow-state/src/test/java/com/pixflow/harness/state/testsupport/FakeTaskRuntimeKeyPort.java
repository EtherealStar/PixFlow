package com.pixflow.harness.state.testsupport;

import com.pixflow.harness.state.port.TaskRuntimeKeyPort;
import com.pixflow.infra.cache.key.CacheKey;
import java.time.Duration;

public class FakeTaskRuntimeKeyPort implements TaskRuntimeKeyPort {
    @Override
    public CacheKey progressKey(String taskId) {
        return new CacheKey("test:progress:" + taskId, Duration.ofMinutes(10), "test-progress");
    }

    @Override
    public CacheKey cancelKey(String taskId) {
        return new CacheKey("test:cancel:" + taskId, Duration.ofMinutes(10), "test-cancel");
    }
}
