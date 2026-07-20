package com.pixflow.agent.memory;

import com.pixflow.module.memory.context.MemoryReference;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 回合入口传给 module-memory 的召回信号。
 *
 * <p>这里不再表达 agent 自己的“通道”或 RRF 参数，只保留
 * {@link com.pixflow.module.memory.context.MemoryContextRequest} 所需的上下文字段。
 */
public record MemoryRecallSignal(
        String conversationId,
        int turnNo,
        String traceId,
        String userMessage,
        List<MemoryReference> references,
        List<String> categoryHints,
        Map<String, Object> metadata,
        int tokenBudget
) {

    public MemoryRecallSignal {
        Objects.requireNonNull(conversationId, "conversationId");
        traceId = traceId == null ? "" : traceId;
        userMessage = userMessage == null ? "" : userMessage;
        references = references == null ? List.of() : List.copyOf(references);
        categoryHints = normalizeList(categoryHints);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        tokenBudget = Math.max(0, tokenBudget);
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
