package com.pixflow.module.memory.insight;

import com.pixflow.infra.vector.Distance;
import com.pixflow.infra.vector.ScoredPoint;
import com.pixflow.infra.vector.VectorFilter;
import com.pixflow.infra.vector.VectorPoint;
import com.pixflow.infra.vector.VectorStore;
import com.pixflow.module.memory.config.MemoryProperties;
import com.pixflow.module.memory.recall.InsightFilter;
import com.pixflow.module.memory.recall.MemoryItem;
import com.pixflow.module.memory.recall.MemoryType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class DefaultInsightVectorRepo implements InsightVectorRepo {
    private final VectorStore vectorStore;
    private final MemoryProperties properties;

    public DefaultInsightVectorRepo(VectorStore vectorStore, MemoryProperties properties) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void ensureCollection(int dimension) {
        vectorStore.ensureCollection(properties.getInsight().getCollection(), dimension, Distance.COSINE);
    }

    @Override
    public void upsertActive(AnalysisInsight insight, float[] vector) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("insight_id", String.valueOf(insight.getId()));
        payload.put("text", insight.getText());
        payload.put("category", valueOrEmpty(insight.getCategory()));
        payload.put("source", valueOrEmpty(insight.getSource()));
        payload.put("confidence", defaulted(insight.getConfidence(), 0.5));
        payload.put("related_sku", valueOrEmpty(insight.getRelatedSku()));
        payload.put("importance", defaulted(insight.getImportance(), 0.5));
        payload.put("decay_score", defaulted(insight.getDecayScore(), 1.0));
        payload.put("created_at", instantString(insight.getCreatedAt()));
        payload.put("last_reinforced_at", instantString(insight.getLastReinforcedAt()));
        vectorStore.upsert(properties.getInsight().getCollection(), List.of(new VectorPoint(vectorPointId(String.valueOf(insight.getId())), vector, payload)));
    }

    @Override
    public List<MemoryItem> search(float[] query, int topK, float threshold, InsightFilter filter) {
        return vectorStore.search(
                        properties.getInsight().getCollection(),
                        query,
                        Math.max(1, topK),
                        threshold,
                        toVectorFilter(filter))
                .stream()
                .map(DefaultInsightVectorRepo::toItem)
                .toList();
    }

    @Override
    public void delete(String insightId) {
        if (insightId == null || insightId.isBlank()) {
            return;
        }
        vectorStore.delete(properties.getInsight().getCollection(), List.of(vectorPointId(insightId)));
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

    public static String vectorPointId(String insightId) {
        return UUID.nameUUIDFromBytes(("analysis_insight:" + insightId).getBytes(StandardCharsets.UTF_8)).toString();
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
        if (conditions.isEmpty()) {
            return VectorFilter.none();
        }
        return VectorFilter.must(conditions.toArray(VectorFilter.Condition[]::new));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Instant instant(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static double defaulted(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String instantString(Instant instant) {
        return instant == null ? "" : instant.toString();
    }
}
