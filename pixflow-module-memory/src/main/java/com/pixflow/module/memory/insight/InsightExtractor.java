package com.pixflow.module.memory.insight;

import com.pixflow.module.memory.ingest.MemoryIngestRequest;
import java.util.List;

public interface InsightExtractor {
    List<ExtractedInsight> extract(MemoryIngestRequest request, List<MemoryItemSnapshot> neighborContext);

    record MemoryItemSnapshot(String id, String text, String category, String relatedSku) {
    }
}
