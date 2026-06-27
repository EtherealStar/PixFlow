package com.pixflow.infra.ai.chat;

import com.pixflow.infra.ai.model.TokenUsage;
import java.util.List;
import java.util.Objects;

/**
 * 阻塞 chat 的完整结果。
 */
public record ChatResult(
        String finalText,
        List<ToolCall> toolCalls,
        StopReason stopReason,
        TokenUsage usage) {

    public ChatResult {
        finalText = finalText == null ? "" : finalText;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        stopReason = Objects.requireNonNull(stopReason, "stopReason");
        usage = Objects.requireNonNull(usage, "usage");
    }
}
