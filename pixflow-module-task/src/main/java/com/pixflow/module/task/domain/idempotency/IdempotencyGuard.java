package com.pixflow.module.task.domain.idempotency;

import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.infra.cache.TaskIdempotencyStore;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.util.Optional;

public class IdempotencyGuard {
    private final TaskIdempotencyStore store;
    private final ProcessTaskMapper mapper;

    public IdempotencyGuard(TaskIdempotencyStore store, ProcessTaskMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public Optional<String> findExistingTaskId(String idempotencyKey) {
        Optional<String> cached = store.get(idempotencyKey);
        if (cached.isPresent()) {
            return cached;
        }
        ProcessTask existing = mapper.findByIdempotencyKey(idempotencyKey);
        if (existing == null || existing.getId() == null) {
            return Optional.empty();
        }
        String taskId = existing.getId().toString();
        store.put(idempotencyKey, taskId);
        return Optional.of(taskId);
    }

    public void remember(String idempotencyKey, String taskId) {
        store.put(idempotencyKey, taskId);
    }
}
