package com.pixflow.module.memory.recall;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.memory.config.MemoryProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryRankerTest {

    @Test
    void ranksByRrfAndLifecycleSignals() {
        MemoryRanker ranker = new MemoryRanker(
                new MemoryProperties(),
                Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC));

        List<MemoryItem> ranked = ranker.rank(List.of(
                item("old", 0.01, 0.5, 0.5, 0.2, "2025-01-01T00:00:00Z"),
                item("fresh", 0.03, 0.9, 0.8, 1.0, "2026-06-27T00:00:00Z")), 10);

        assertThat(ranked).extracting(MemoryItem::id).containsExactly("fresh", "old");
        assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
    }

    private static MemoryItem item(
            String id,
            double rrf,
            double confidence,
            double importance,
            double decay,
            String reinforcedAt) {
        return new MemoryItem(id, MemoryType.INSIGHT, id, "test", "", "", 0, rrf,
                confidence, importance, decay, Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse(reinforcedAt), Map.of());
    }
}
