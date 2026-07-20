package com.pixflow.app.activity;

import java.time.Instant;
import java.util.Objects;

public record ActivityView(
        String activityId,
        ActivityKind kind,
        ActivityStatus status,
        ActivityProgress progress,
        String conversationId,
        String packageId,
        String taskId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        ActivityActions allowedActions,
        long sequence) {
    public ActivityView {
        activityId = requireText(activityId, "activityId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(allowedActions, "allowedActions");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
    }

    public ActivityView withSequence(long value) {
        return new ActivityView(activityId, kind, status, progress, conversationId, packageId, taskId,
                createdAt, startedAt, finishedAt, allowedActions, value);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
