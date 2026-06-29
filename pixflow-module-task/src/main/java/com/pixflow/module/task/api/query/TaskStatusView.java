package com.pixflow.module.task.api.query;

import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.TaskType;
import java.time.Instant;

public record TaskStatusView(
        String taskId,
        TaskType taskType,
        TaskStatus status,
        int total,
        int done,
        int failed,
        int skipped,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {
}
