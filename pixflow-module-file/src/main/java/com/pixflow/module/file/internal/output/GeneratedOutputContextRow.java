package com.pixflow.module.file.internal.output;

import java.time.Instant;

public record GeneratedOutputContextRow(
        String taskId,
        String conversationId,
        String conversationTitleSnapshot,
        String taskType,
        Instant taskCreatedAt,
        Instant taskFinishedAt) {
}
