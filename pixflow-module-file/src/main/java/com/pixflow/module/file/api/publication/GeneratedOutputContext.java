package com.pixflow.module.file.api.publication;

import java.time.Instant;

/** Generated Image 发布时冻结的 Outputs 分组事实。 */
public record GeneratedOutputContext(
        String conversationId,
        String conversationTitleSnapshot,
        String taskId,
        OutputTaskType taskType,
        Instant taskCreatedAt) {
    public GeneratedOutputContext {
        conversationId = requireText(conversationId, "conversationId");
        conversationTitleSnapshot = conversationTitleSnapshot == null ? "" : conversationTitleSnapshot.trim();
        taskId = requireText(taskId, "taskId");
        if (taskType == null || taskCreatedAt == null) {
            throw new IllegalArgumentException("taskType and taskCreatedAt are required");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
