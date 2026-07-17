package com.pixflow.module.memory.insight;

import com.pixflow.infra.vector.Distance;
import com.pixflow.infra.vector.ScoredPoint;
import com.pixflow.infra.vector.VectorFilter;
import com.pixflow.infra.vector.VectorSearch;
import com.pixflow.module.memory.config.MemoryProperties;
import com.pixflow.module.memory.recall.InsightFilter;
import com.pixflow.module.memory.recall.MemoryItem;
import com.pixflow.module.memory.recall.MemoryType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DefaultInsightVectorSearch implements InsightVectorSearch {
    private final VectorSearch vectorSearch;

    private final MemoryProperties properties;

    public DefaultInsightVectorSearch(VectorSearch vectorSearch, MemoryProperties properties) {
        this.vectorSearch = Objects.requireNonNull(vectorSearch, "vectorSearch");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void verifyCollection(int dimension) {
        vectorSearch.verifyCollection(properties.getInsight().getCollection(), dimension, Distance.COSINE);
    }

    @Override
    public List<MemoryItem> search(float[] query, int topK, float threshold, InsightFilter filter) {
        return vectorSearch.search(
                        properties.getInsight().getCollection(),
                        query,
                        Math.max(1, topK),
                        threshold,
                        toVectorFilter(filter))
                .stream()
                .map(DefaultInsightVectorSearch::toItem)
                .toList();
    }

    private static MemoryItem toItem(ScoredPoint point) {
        Map<String, Object> payload = point.payload();
        String insightId = string(payload.get("insight_id"));
        return new MemoryItem(
                insightId.isBlank() ? point.id() : insightId,
                MemoryType.INSIGHT,
                string(payload.get("text")),
                string(payload.get("source")),
                string(payload.get("category")),
                string(payload.get("related_sku")),
                point.score(),
                0,
                number(payload.get("confidence"), 0.5),
                number(payload.get("importance"), 0.5),
                number(payload.get("decay_score"), 1.0),
                instant(payload.get("created_at")),
                instant(payload.get("last_reinforced_at")),
                Map.of("vector_score", point.score()));
    }

    private static VectorFilter toVectorFilter(InsightFilter filter) {
        if (filter == null) {
            return VectorFilter.none();
        }
        List<VectorFilter.Condition> conditions = new ArrayList<>();
        if (!filter.categories().isEmpty()) {
            conditions.add(VectorFilter.matchAny("category", new ArrayList<>(filter.categories())));
        }
        if (!filter.skuIds().isEmpty()) {
            conditions.add(VectorFilter.matchAny("related_sku", new ArrayList<>(filter.skuIds())));
        }
        if (filter.minConfidence() > 0) {
            conditions.add(VectorFilter.range("confidence", filter.minConfidence(), null));
        }
        return conditions.isEmpty()
                ? VectorFilter.none()
                : VectorFilter.must(conditions.toArray(VectorFilter.Condition[]::new));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Instant instant(Object value) {
        try {
            return value == null || String.valueOf(value).isBlank() ? null : Instant.parse(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
