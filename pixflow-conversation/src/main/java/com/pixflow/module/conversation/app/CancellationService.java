package com.pixflow.module.conversation.app;

import com.pixflow.module.task.api.TaskCommandService;
import com.pixflow.module.task.api.command.CancelTaskCommand;
import com.pixflow.module.task.api.command.TaskId;

public class CancellationService {
    private final ConversationService conversationService;
    private final TaskCommandService taskCommandService;

    public CancellationService(ConversationService conversationService, TaskCommandService taskCommandService) {
        this.conversationService = conversationService;
        this.taskCommandService = taskCommandService;
    }

    public CancellationResult cancel(String conversationId, String taskId) {
        conversationService.requireActive(conversationId);
        boolean cancelled = taskCommandService.cancel(new CancelTaskCommand(new TaskId(taskId), "user_cancelled", conversationId));
        return new CancellationResult(taskId, cancelled);
    }
}
