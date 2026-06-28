package com.pixflow.module.memory.insight;

import com.pixflow.module.memory.recall.MemoryItem;
import java.util.List;
import java.util.Map;

public record InsightRecallResult(List<MemoryItem> items, boolean degraded, Map<String, Object> trace) {
    public InsightRecallResult {
        items = items == null ? List.of() : List.copyOf(items);
        trace = trace == null ? Map.of() : Map.copyOf(trace);
    }

    public static InsightRecallResult empty() {
        return new InsightRecallResult(List.of(), false, Map.of("candidate_count", 0));
    }
}
