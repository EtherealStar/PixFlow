package com.pixflow.harness.state.model;

import java.time.Instant;
import java.util.Objects;

public record ExecutionStateSnapshot(
        String taskId,
        TaskRunStatus status,
        ProgressView progress,
        boolean cancelRequested,
        Instant snapshotAt) {

    public ExecutionStateSnapshot {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        taskId = taskId.trim();
        status = Objects.requireNonNull(status, "status");
        snapshotAt = Objects.requireNonNull(snapshotAt, "snapshotAt");
    }
}
