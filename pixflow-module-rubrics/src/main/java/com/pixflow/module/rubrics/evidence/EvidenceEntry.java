package com.pixflow.module.rubrics.evidence;

import com.pixflow.module.rubrics.model.EvidenceType;
import java.util.Map;
import java.time.Instant;

public record EvidenceEntry(String id, EvidenceType type, String sourceRef, String contentHash,
                            Instant capturedAt, Map<String, Object> metadata) {
    public EvidenceEntry {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
