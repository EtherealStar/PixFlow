package com.pixflow.module.task.api.event;

import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.TaskType;
import java.time.Instant;

public record TaskCompletedEvent(String taskId, TaskType taskType, TaskStatus status,
                                 int total, int succeeded, int failed, Instant occurredAt) {
}
