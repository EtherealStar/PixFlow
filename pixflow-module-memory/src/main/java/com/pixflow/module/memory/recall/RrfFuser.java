package com.pixflow.module.memory.recall;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RrfFuser {
    public List<MemoryItem> fuse(List<List<MemoryItem>> rankedLists, int k) {
        int safeK = Math.max(1, k);
        Map<String, Accumulator> accumulators = new LinkedHashMap<>();
        if (rankedLists == null) {
            return List.of();
        }
        for (List<MemoryItem> list : rankedLists) {
            if (list == null) {
                continue;
            }
            for (int index = 0; index < list.size(); index++) {
                MemoryItem item = list.get(index);
                double contribution = 1.0d / (safeK + index + 1);
                accumulators.computeIfAbsent(item.id(), ignored -> new Accumulator(item)).score += contribution;
            }
        }
        return accumulators.values().stream()
                .map(accumulator -> accumulator.item.withScores(accumulator.score, accumulator.score))
                .sorted(Comparator.comparingDouble(MemoryItem::rrfScore).reversed()
                        .thenComparing(MemoryItem::id))
                .toList();
    }

    @SafeVarargs
    public final List<MemoryItem> fuse(int k, List<MemoryItem>... rankedLists) {
        List<List<MemoryItem>> lists = new ArrayList<>();
        if (rankedLists != null) {
            lists.addAll(List.of(rankedLists));
        }
        return fuse(lists, k);
    }

    private static class Accumulator {
        private final MemoryItem item;

        private double score;

        private Accumulator(MemoryItem item) {
            this.item = item;
        }
    }
}
