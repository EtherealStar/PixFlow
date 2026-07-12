package com.pixflow.infra.ai.vision;

import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.model.ChatOptions;
import com.pixflow.infra.ai.model.ModelRole;
import java.util.List;
import java.util.Objects;

/**
 * 多模态请求。
 */
public record VisionRequest(ModelRole role, List<ChatMessage> messages, ChatOptions options) {
    public VisionRequest {
        role = Objects.requireNonNull(role, "role");
        if (role != ModelRole.VISION && role != ModelRole.RUBRICS_JUDGE_VISION) {
            throw new IllegalArgumentException("vision request requires a vision-capable role");
        }
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    }
}
