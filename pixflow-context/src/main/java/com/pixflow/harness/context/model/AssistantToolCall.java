package com.pixflow.harness.context.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * provider-neutral 的 assistant tool-call 载荷。
 *
 * <p>模型请求工具时，assistant 消息必须保留完整 id、name 与 argumentsJson，
 * 下一轮才能投影成供应商协议里的 assistant tool_calls，并与 tool result 配对。
 */
public record AssistantToolCall(String id, String name, String argumentsJson) {
    public AssistantToolCall {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("assistant tool call id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("assistant tool call name must not be blank");
        }
        id = id.trim();
        name = name.trim();
        argumentsJson = Objects.requireNonNullElse(argumentsJson, "{}");
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("name", name);
        value.put("argumentsJson", argumentsJson);
        return Map.copyOf(value);
    }

    public static AssistantToolCall fromMetadataMap(Map<?, ?> value) {
        if (value == null) {
            throw new IllegalArgumentException("assistant tool call metadata must not be null");
        }
        return new AssistantToolCall(
                text(value.get("id"), "id"),
                text(value.get("name"), "name"),
                value.get("argumentsJson") == null ? "{}" : String.valueOf(value.get("argumentsJson")));
    }

    private static String text(Object value, String field) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("assistant tool call " + field + " must not be blank");
        }
        return String.valueOf(value);
    }
}
