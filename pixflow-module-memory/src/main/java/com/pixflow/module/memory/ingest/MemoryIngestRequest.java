package com.pixflow.module.memory.ingest;

import com.pixflow.module.memory.context.MemoryContext;
import java.util.List;
import java.util.Map;

public record MemoryIngestRequest(
        String conversationId,
        int turnNo,
        String traceId,
        String userPrompt,
        String assistantAnswer,
        MemoryContext recalledMemory,
        List<String> toolObservations,
        List<String> skuIds,
        List<String> categories,
        Map<String, Object> evidence) {

    public MemoryIngestRequest {
        toolObservations = toolObservations == null ? List.of() : List.copyOf(toolObservations);
        skuIds = skuIds == null ? List.of() : List.copyOf(skuIds);
        categories = categories == null ? List.of() : List.copyOf(categories);
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}
