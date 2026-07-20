package com.pixflow.module.task.internal.query;

import com.pixflow.module.task.api.authorization.TaskAuthorizationFacts;
import com.pixflow.module.task.api.authorization.TaskAuthorizationFactsQuery;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.util.Objects;
import java.util.Optional;

/** 在 Task 内部把持久化状态投影为稳定的公开授权事实。 */
public final class TaskAuthorizationFactsQueryImpl implements TaskAuthorizationFactsQuery {
    private final ProcessTaskMapper taskMapper;

    public TaskAuthorizationFactsQueryImpl(ProcessTaskMapper taskMapper) {
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
    }

    @Override
    public Optional<TaskAuthorizationFacts> find(String taskId) {
        final long parsedId;
        try {
            parsedId = Long.parseLong(taskId);
        } catch (RuntimeException invalidId) {
            return Optional.empty();
        }
        if (parsedId <= 0) {
            return Optional.empty();
        }
        ProcessTask task = taskMapper.selectById(parsedId);
        if (task == null || task.getConversationId() == null || task.getConversationId().isBlank()
                || task.getStatus() == null) {
            return Optional.empty();
        }
        TaskStatus status = task.getStatus();
        return Optional.of(new TaskAuthorizationFacts(
                String.valueOf(parsedId),
                task.getConversationId(),
                !status.terminal(),
                status == TaskStatus.FAILED || status == TaskStatus.PARTIAL,
                status.terminal(),
                status == TaskStatus.COMPLETED || status == TaskStatus.PARTIAL));
    }
}
