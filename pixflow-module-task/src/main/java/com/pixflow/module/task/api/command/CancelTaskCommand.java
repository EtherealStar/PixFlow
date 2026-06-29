package com.pixflow.module.task.api.command;

import java.util.Objects;

public record CancelTaskCommand(TaskId taskId, String reason, String requestedBy) {
    public CancelTaskCommand {
        taskId = Objects.requireNonNull(taskId, "taskId");
        reason = reason == null || reason.isBlank() ? "user_cancelled" : reason.trim();
        requestedBy = requestedBy == null ? null : requestedBy.trim();
    }
}
