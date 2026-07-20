package com.pixflow.infra.ai.vision;

import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.ChatResult;
import java.util.Objects;

/**
 * 默认视觉 client。
 */
public final class DefaultVisionModelClient implements VisionModelClient {
    private final ChatModelClient chatModelClient;

    public DefaultVisionModelClient(ChatModelClient chatModelClient) {
        this.chatModelClient = Objects.requireNonNull(chatModelClient, "chatModelClient");
    }

    @Override
    public ChatResult call(VisionRequest request) {
        Objects.requireNonNull(request, "request");
        // 视觉底层沿用 OpenAI-compatible chat completions，但上层只看 VisionModelClient。
        return chatModelClient.call(new ChatRequest(
                request.role(),
                request.messages(),
                request.toolSchemas(),
                request.toolChoice(),
                request.options(),
                request.attemptBudget()));
    }
}
