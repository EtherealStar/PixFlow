package com.pixflow.harness.tools;

import com.pixflow.harness.hooks.payload.RuntimeScope;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolInvocation(
        String toolCallId,
        String toolName,
        Map<String, Object> arguments,
        String conversationId,
        Integer turnNo,
        String traceId,
        RuntimeScope runtimeScope,
        Map<String, Object> metadata) {

    public ToolInvocation {
        arguments = immutableCopy(arguments);
        metadata = immutableCopy(metadata);
        runtimeScope = runtimeScope == null ? RuntimeScope.main() : runtimeScope;
    }

    private static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
