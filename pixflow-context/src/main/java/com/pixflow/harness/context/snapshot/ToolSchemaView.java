package com.pixflow.harness.context.snapshot;

import java.util.Map;

public record ToolSchemaView(String name, String description, Map<String, Object> schema) {
    public ToolSchemaView {
        schema = Map.copyOf(schema == null ? Map.of() : schema);
    }
}
