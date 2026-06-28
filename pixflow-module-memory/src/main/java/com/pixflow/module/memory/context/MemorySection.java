package com.pixflow.module.memory.context;

import com.pixflow.module.memory.recall.MemoryItem;
import java.util.List;
import java.util.Map;

public record MemorySection(
        String name,
        String renderedText,
        List<MemoryItem> items,
        int tokenEstimate,
        Map<String, Object> trace) {

    public MemorySection {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        renderedText = renderedText == null ? "" : renderedText;
        items = items == null ? List.of() : List.copyOf(items);
        trace = trace == null ? Map.of() : Map.copyOf(trace);
    }
}
