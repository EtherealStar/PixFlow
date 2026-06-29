package com.pixflow.harness.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolExecutionResult(String toolCallId, String toolName, String content, boolean error, Map<String, Object> metadata) {
    public ToolExecutionResult {
        content = content == null ? "" : content;
        metadata = immutableCopy(metadata);
    }

    public static ToolExecutionResult success(String toolCallId, String toolName, String content, Map<String, Object> metadata) {
        return new ToolExecutionResult(toolCallId, toolName, content, false, metadata);
    }

    public static ToolExecutionResult error(String toolCallId, String toolName, String content, Map<String, Object> metadata) {
        return new ToolExecutionResult(toolCallId, toolName, content, true, metadata);
    }

    private static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
