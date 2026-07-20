package com.pixflow.infra.ai.chat;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.ai.model.TokenUsage;
import java.util.List;

/**
 * chat 流式事件。
 */
public sealed interface ChatStreamEvent
        permits ChatStreamEvent.TextDelta, ChatStreamEvent.AttemptReset, ChatStreamEvent.Completed {
    record TextDelta(String text, int blockIndex) implements ChatStreamEvent {
    }

    record AttemptReset(PixFlowException error, int nextAttempt, int retriesRemaining) implements ChatStreamEvent {
    }

    record Completed(String finalText, List<ToolCall> toolCalls, StopReason stopReason, TokenUsage usage)
            implements ChatStreamEvent {
        public Completed {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }
}
