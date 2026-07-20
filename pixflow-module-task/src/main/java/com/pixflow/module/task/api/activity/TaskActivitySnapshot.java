package com.pixflow.module.task.api.activity;

import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.TaskType;
import java.time.Instant;
import java.util.Objects;

public record TaskActivitySnapshot(
        String taskId,
        String conversationId,
        long packageId,
        long revision,
        TaskType taskType,
        TaskStatus status,
        int completed,
        int total,
        int failed,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        boolean cancellable,
        boolean retryable,
        boolean clearable) {
    public TaskActivitySnapshot {
        taskId = requireText(taskId, "taskId");
        conversationId = requireText(conversationId, "conversationId");
        Objects.requireNonNull(taskType, "taskType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (packageId <= 0 || revision < 0 || completed < 0 || total < 0 || failed < 0) {
            throw new IllegalArgumentException("invalid task activity snapshot");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
