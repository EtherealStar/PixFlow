package com.pixflow.module.memory.insight;

import com.pixflow.module.memory.recall.InsightFilter;
import com.pixflow.module.memory.recall.MemoryItem;
import com.pixflow.module.memory.recall.MemoryType;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.Objects;

public class MybatisInsightKeywordSearch implements InsightKeywordSearch {
    private final InsightDocMapper mapper;

    public MybatisInsightKeywordSearch(InsightDocMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public List<MemoryItem> search(String query, InsightFilter filter, int topK, Instant asOf) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return mapper.fulltextSearch(
                        query.trim(),
                        filter == null ? InsightFilter.empty() : filter,
                        Math.max(1, topK),
                        asOf == null ? Instant.EPOCH : asOf)
                .stream()
                .map(MybatisInsightKeywordSearch::toItem)
                .toList();
    }

    static MemoryItem toItem(AnalysisInsight insight) {
        return new MemoryItem(
                String.valueOf(insight.getId()),
                MemoryType.INSIGHT,
                insight.getText(),
                insight.getSource(),
                insight.getCategory(),
                insight.getRelatedSku(),
                0,
                0,
                defaulted(insight.getConfidence(), 0.5),
                defaulted(insight.getImportance(), 0.5),
                defaulted(insight.getDecayScore(), 1.0),
                insight.getCreatedAt(),
                insight.getLastReinforcedAt(),
                Map.of("keyword", true));
    }

    private static double defaulted(Double value, double fallback) {
        return value == null ? fallback : value;
    }
}
