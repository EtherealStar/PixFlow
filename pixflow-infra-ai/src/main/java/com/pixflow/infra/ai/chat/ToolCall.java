package com.pixflow.infra.ai.chat;

import java.util.Objects;

/**
 * 模型声明的工具调用。
 */
public record ToolCall(String id, String name, String argumentsJson) {
    public ToolCall {
        name = requireText(name, "name");
        argumentsJson = Objects.requireNonNull(argumentsJson, "argumentsJson");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
