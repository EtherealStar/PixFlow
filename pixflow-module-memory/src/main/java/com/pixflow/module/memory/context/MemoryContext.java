package com.pixflow.module.memory.context;

import java.util.List;
import java.util.Map;

public record MemoryContext(
        String conversationId,
        int turnNo,
        List<MemorySection> sections,
        Map<String, Object> recallTrace,
        boolean degraded) {

    public MemoryContext {
        sections = sections == null ? List.of() : List.copyOf(sections);
        recallTrace = recallTrace == null ? Map.of() : Map.copyOf(recallTrace);
    }

    public MemorySection section(String name) {
        return sections.stream()
                .filter(section -> section.name().equals(name))
                .findFirst()
                .orElse(null);
    }
}
