package com.pixflow.harness.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolHandlerOutput(String content, Map<String, Object> metadata) {
    public ToolHandlerOutput {
        content = content == null ? "" : content;
        metadata = immutableCopy(metadata);
    }

    public static ToolHandlerOutput of(String content) {
        return new ToolHandlerOutput(content, Map.of());
    }

    private static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
