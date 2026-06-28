package com.pixflow.harness.state.runtime;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.state.error.StateErrorCode;
import com.pixflow.harness.state.port.TaskRuntimeKeyPort;
import com.pixflow.infra.cache.store.CacheStore;

public class DefaultCancellationReader implements CancellationReader {
    private final CacheStore cacheStore;
    private final TaskRuntimeKeyPort keyPort;

    public DefaultCancellationReader(CacheStore cacheStore, TaskRuntimeKeyPort keyPort) {
        this.cacheStore = cacheStore;
        this.keyPort = keyPort;
    }

    @Override
    public boolean isCancelRequested(String taskId) {
        return cacheStore.get(keyPort.cancelKey(taskId), Boolean.class).orElse(false);
    }

    @Override
    public void throwIfCancelled(String taskId) {
        if (isCancelRequested(taskId)) {
            throw new PixFlowException(StateErrorCode.STATE_TASK_CANCELLED, "Task cancellation requested: " + taskId);
        }
    }
}
