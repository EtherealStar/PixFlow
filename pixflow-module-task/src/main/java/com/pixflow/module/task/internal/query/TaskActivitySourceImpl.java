package com.pixflow.module.task.internal.query;

import com.pixflow.module.task.api.activity.TaskActivitySnapshot;
import com.pixflow.module.task.api.activity.TaskActivitySource;
import com.pixflow.module.task.api.query.PageResult;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.util.Optional;

public final class TaskActivitySourceImpl implements TaskActivitySource {
    private final ProcessTaskMapper tasks;

    private final ProcessResultMapper results;

    public TaskActivitySourceImpl(ProcessTaskMapper tasks, ProcessResultMapper results) {
        this.tasks = tasks;
        this.results = results;
    }

    @Override
    public Optional<TaskActivitySnapshot> find(String taskId) {
        return Optional.ofNullable(tasks.selectById(Long.parseLong(taskId))).map(this::toSnapshot);
    }

    @Override
    public PageResult<TaskActivitySnapshot> listCurrent(int page, int size) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be 1-based");
        }
        long offset = (page - 1L) * size;
        return new PageResult<>(
                tasks.pageActivities(offset, size).stream().map(this::toSnapshot).toList(),
                tasks.countActivities(), page, size);
    }

    private TaskActivitySnapshot toSnapshot(ProcessTask task) {
        long taskId = task.getId();
        int failed = results.countByStatus(taskId, ResultStatus.FAILED);
        int skipped = results.countByStatus(taskId, ResultStatus.SKIPPED);
        int completed = value(task.getDoneCount()) + failed + skipped;
        TaskStatus status = task.getStatus();
        return new TaskActivitySnapshot(
                Long.toString(taskId), task.getConversationId(), task.getPackageId(),
                task.getUpdatedAt().toEpochMilli(), task.getTaskType(), status,
                completed, value(task.getTotalCount()), failed, task.getCreatedAt(),
                task.getStartedAt(), task.getFinishedAt(),
                status == TaskStatus.PENDING || status == TaskStatus.QUEUED || status == TaskStatus.RUNNING,
                status == TaskStatus.FAILED || status == TaskStatus.PARTIAL,
                status.terminal());
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
