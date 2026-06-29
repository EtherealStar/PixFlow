package com.pixflow.harness.tools.schema;

import com.pixflow.harness.tools.ToolDescriptor;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolSchemaExporter {
    private ToolSchemaExporter() {
    }

    public static Map<String, Object> export(ToolDescriptor descriptor) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", descriptor.name());
        schema.put("description", descriptor.description());
        schema.put("inputSchema", descriptor.inputSchema());
        schema.put("outputSchema", descriptor.outputSchema());
        return schema;
    }
}
