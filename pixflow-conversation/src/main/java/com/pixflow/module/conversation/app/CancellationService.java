package com.pixflow.module.conversation.app;

import com.pixflow.module.task.api.TaskCommandService;
import com.pixflow.module.task.api.command.CancelTaskCommand;
import com.pixflow.common.error.BusinessException;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import java.util.Map;

public class CancellationService {
    private final ConversationService conversationService;
    private final TaskCommandService taskCommandService;

    public CancellationService(ConversationService conversationService, TaskCommandService taskCommandService) {
        this.conversationService = conversationService;
        this.taskCommandService = taskCommandService;
    }

    public CancellationResult cancel(long ownerUserId, String conversationId, String taskId) {
        conversationService.requireActive(ownerUserId, conversationId);
        long parsedTaskId = parseTaskId(taskId);
        boolean cancelled = taskCommandService.cancel(
                new CancelTaskCommand(parsedTaskId, conversationId, ownerUserId, "user_cancelled"));
        return new CancellationResult(taskId, cancelled);
    }

    private static long parseTaskId(String taskId) {
        try {
            long parsed = Long.parseLong(taskId == null ? "" : taskId.trim());
            if (parsed <= 0L) {
                throw new NumberFormatException("taskId must be positive");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new BusinessException(ConversationErrorCode.TASK_ID_INVALID,
                    "task id is invalid",
                    ex,
                    Map.of("taskId", taskId == null ? "" : taskId));
        }
    }
}
