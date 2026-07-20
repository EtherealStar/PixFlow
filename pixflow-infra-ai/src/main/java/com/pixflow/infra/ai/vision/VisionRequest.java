package com.pixflow.infra.ai.vision;

import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.model.ChatOptions;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.chat.ToolChoice;
import com.pixflow.infra.ai.chat.ToolSchema;
import com.pixflow.infra.ai.resilience.AttemptBudget;
import java.util.List;
import java.util.Objects;

/**
 * 多模态请求。
 */
public record VisionRequest(
        ModelRole role,
        List<ChatMessage> messages,
        List<ToolSchema> toolSchemas,
        ToolChoice toolChoice,
        ChatOptions options,
        AttemptBudget attemptBudget) {
    public VisionRequest {
        role = Objects.requireNonNull(role, "role");
        if (role != ModelRole.VISION && role != ModelRole.RUBRICS_JUDGE_VISION) {
            throw new IllegalArgumentException("vision request requires a vision-capable role");
        }
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        toolSchemas = toolSchemas == null ? List.of() : List.copyOf(toolSchemas);
        toolChoice = toolChoice == null ? ToolChoice.AUTO : toolChoice;
        attemptBudget = attemptBudget == null ? AttemptBudget.unbounded() : attemptBudget;
    }
}
