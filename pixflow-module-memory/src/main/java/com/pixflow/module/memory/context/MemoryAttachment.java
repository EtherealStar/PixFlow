package com.pixflow.module.memory.context;

import java.util.Map;

public record MemoryAttachment(String fileName, String skuId, String category, Map<String, Object> metadata) {
    public MemoryAttachment {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
