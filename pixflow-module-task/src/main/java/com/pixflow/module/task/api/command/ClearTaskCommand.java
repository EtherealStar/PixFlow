package com.pixflow.module.task.api.command;

import java.util.Objects;

public record ClearTaskCommand(long taskId, String conversationId, long requesterUserId) {
    public ClearTaskCommand {
        if (taskId <= 0 || requesterUserId <= 0) {
            throw new IllegalArgumentException("taskId and requesterUserId must be positive");
        }
        conversationId = Objects.requireNonNull(conversationId, "conversationId").trim();
        if (conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
    }
}
