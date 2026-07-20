package com.pixflow.module.memory.recall;

import com.pixflow.module.memory.config.MemoryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public class MemoryRanker {
    private final MemoryProperties properties;

    private final Clock clock;

    public MemoryRanker(MemoryProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public List<MemoryItem> rank(List<MemoryItem> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        MemoryProperties.Rank rank = properties.getInsight().getRank();
        Instant now = clock.instant();
        return candidates.stream()
                .map(item -> {
                    double recency = recencyBoost(item, now);
                    double score = rank.getRrfWeight() * item.rrfScore()
                            + rank.getConfidenceWeight() * item.confidence()
                            + rank.getImportanceWeight() * item.importance()
                            + rank.getDecayWeight() * item.decayScore()
                            + rank.getRecencyWeight() * recency;
                    return item.withScores(score, item.rrfScore());
                })
                .filter(item -> item.score() >= properties.getInsight().getRecall().getMinFinalScore())
                .sorted(Comparator.comparingDouble(MemoryItem::score).reversed()
                        .thenComparing(MemoryItem::lastReinforcedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(MemoryItem::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(MemoryItem::id))
                .limit(Math.max(0, topN))
                .toList();
    }

    private static double recencyBoost(MemoryItem item, Instant now) {
        Instant basis = item.lastReinforcedAt() != null ? item.lastReinforcedAt() : item.createdAt();
        if (basis == null) {
            return 0;
        }
        long days = Math.max(0, Duration.between(basis, now).toDays());
        // 最近强化的记忆只作为同相关性结果的轻量加分，不能覆盖 RRF 相关性。
        return 1.0d / (1.0d + days);
    }
}
