package com.pixflow.module.task.api.query;

import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.TaskType;
import java.time.Instant;

public record TaskStatusView(
    String taskId,
    TaskType taskType,
    TaskStatus status,
    Progress progress,
    int skipped,
    String lastError,
    String retryOfTaskId,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt) {
  public record Progress(int done, int total, int failed) { }
}
