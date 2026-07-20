package com.pixflow.module.memory.context;

import java.util.List;
import java.util.Map;

public record MemoryContextRequest(
        String conversationId,
        int turnNo,
        String traceId,
        String userPrompt,
        List<MemoryReference> references,
        List<String> categoryHints,
        Map<String, Object> metadata,
        int tokenBudget) {

    public MemoryContextRequest {
        references = normalizeReferences(references);
        categoryHints = normalizeList(categoryHints);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        tokenBudget = Math.max(0, tokenBudget);
    }

    private static List<MemoryReference> normalizeReferences(List<MemoryReference> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toMap(
                        MemoryReference::referenceKey,
                        value -> value,
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new))
                .values().stream().toList();
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
