package com.pixflow.module.task.api.command;

import java.util.Objects;

public record CancelTaskCommand(long taskId, String conversationId, long requesterUserId, String reason) {
    public CancelTaskCommand {
        if (taskId <= 0L) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        conversationId = Objects.requireNonNull(conversationId, "conversationId").trim();
        if (conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        if (requesterUserId <= 0L) {
            throw new IllegalArgumentException("requesterUserId must be positive");
        }
        reason = reason == null || reason.isBlank() ? "user_cancelled" : reason.trim();
    }
}
