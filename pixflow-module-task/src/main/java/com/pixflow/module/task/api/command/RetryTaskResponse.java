package com.pixflow.module.task.api.command;

import com.pixflow.module.task.domain.model.TaskStatus;

public record RetryTaskResponse(
        String taskId,
        String retryOfTaskId,
        int selectedUnitCount,
        TaskStatus status) {
}
