package com.pixflow.module.memory.insight;

import java.time.Instant;
import java.util.List;

public record ExtractedInsight(
        String text,
        String category,
        String source,
        double confidence,
        String relatedSku,
        double importance,
        Instant expiresAt,
        List<String> conflictsWith) {

    public ExtractedInsight {
        text = text == null ? "" : text.trim();
        category = category == null ? "" : category.trim();
        source = source == null || source.isBlank() ? "turn_consolidation" : source.trim();
        confidence = clamp(confidence, 0.5);
        relatedSku = relatedSku == null ? "" : relatedSku.trim();
        importance = clamp(importance, 0.5);
        conflictsWith = conflictsWith == null ? List.of() : List.copyOf(conflictsWith);
    }

    public boolean valid() {
        return !text.isBlank();
    }

    private static double clamp(double value, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        if (value < 0) {
            return 0;
        }
        if (value > 1) {
            return 1;
        }
        return value;
    }
}
