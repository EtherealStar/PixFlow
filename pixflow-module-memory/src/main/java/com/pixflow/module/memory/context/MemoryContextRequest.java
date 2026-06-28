package com.pixflow.module.memory.context;

import java.util.List;
import java.util.Map;

public record MemoryContextRequest(
        String conversationId,
        int turnNo,
        String traceId,
        String userPrompt,
        List<MemoryAttachment> attachments,
        String packageId,
        String taskId,
        List<String> skuIds,
        List<String> categoryHints,
        Map<String, Object> metadata,
        Integer tokenBudget) {

    public MemoryContextRequest {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        skuIds = normalizeList(skuIds);
        categoryHints = normalizeList(categoryHints);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
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
