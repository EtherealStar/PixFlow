package com.pixflow.module.memory.recall;

import java.time.Instant;
import java.util.Map;

public record MemoryItem(
        String id,
        MemoryType type,
        String text,
        String source,
        String category,
        String relatedSku,
        double score,
        double rrfScore,
        double confidence,
        double importance,
        double decayScore,
        Instant createdAt,
        Instant lastReinforcedAt,
        Map<String, Object> attributes) {

    public MemoryItem {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        text = text == null ? "" : text;
        source = source == null ? "" : source;
        category = category == null ? "" : category;
        relatedSku = relatedSku == null ? "" : relatedSku;
        score = finite(score, "score");
        rrfScore = finite(rrfScore, "rrfScore");
        confidence = clamp01(confidence);
        importance = clamp01(importance);
        decayScore = clamp01(decayScore);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public MemoryItem withScores(double newScore, double newRrfScore) {
        return new MemoryItem(id, type, text, source, category, relatedSku, newScore, newRrfScore,
                confidence, importance, decayScore, createdAt, lastReinforcedAt, attributes);
    }

    private static double clamp01(double value) {
        finite(value, "value");
        if (value < 0) {
            return 0;
        }
        if (value > 1) {
            return 1;
        }
        return value;
    }

    private static double finite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }
}
