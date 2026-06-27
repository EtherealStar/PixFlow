package com.pixflow.infra.ai.chat;

import com.pixflow.infra.ai.model.ChatOptions;
import com.pixflow.infra.ai.model.ModelRole;
import java.util.List;
import java.util.Objects;

/**
 * 文本 chat 请求。
 */
public record ChatRequest(
        ModelRole role,
        List<ChatMessage> messages,
        List<ToolSchema> toolSchemas,
        ToolChoice toolChoice,
        ChatOptions options) {

    public ChatRequest {
        role = role == null ? ModelRole.PRIMARY_CHAT : role;
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        toolSchemas = toolSchemas == null ? List.of() : List.copyOf(toolSchemas);
        toolChoice = toolChoice == null ? ToolChoice.AUTO : toolChoice;
    }
}
