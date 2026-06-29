package com.pixflow.module.task.api.event;

import com.pixflow.module.task.domain.model.TaskStatus;
import java.time.Instant;

public record ProgressEvent(String taskId, int total, int done, int failed, int skipped,
                            TaskStatus status, Instant occurredAt) {
}
