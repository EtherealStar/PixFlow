package com.pixflow.module.task.api.authorization;

import java.util.Objects;

/** 供组合根做授权和路由的最小任务事实，不暴露任务实体或持久化状态。 */
public record TaskAuthorizationFacts(
        String taskId,
        String conversationId,
        boolean cancellable,
        boolean retryable,
        boolean deletable,
        boolean downloadable) {
    public TaskAuthorizationFacts {
        taskId = requireText(taskId, "taskId");
        conversationId = requireText(conversationId, "conversationId");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
