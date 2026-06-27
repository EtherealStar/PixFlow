package com.pixflow.infra.ai.vision;

import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.model.ChatOptions;
import java.util.List;
import java.util.Objects;

/**
 * 多模态请求。
 */
public record VisionRequest(List<ChatMessage> messages, ChatOptions options) {
    public VisionRequest {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    }
}
