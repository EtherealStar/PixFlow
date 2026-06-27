package com.pixflow.infra.ai.chat;

import java.util.Objects;

/**
 * 可见工具的 schema 说明。
 */
public record ToolSchema(String name, String description, String jsonSchema) {
    public ToolSchema {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        jsonSchema = Objects.requireNonNull(jsonSchema, "jsonSchema");
    }
}
