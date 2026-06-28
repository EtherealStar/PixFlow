package com.pixflow.module.memory.recall;

import java.util.List;

public record RecallSignals(
        List<String> skuIds,
        List<String> categories,
        List<String> intents,
        List<String> metricTerms) {

    public RecallSignals {
        skuIds = normalize(skuIds);
        categories = normalize(categories);
        intents = normalize(intents);
        metricTerms = normalize(metricTerms);
    }

    public boolean hasInsightSignal() {
        return !categories.isEmpty() || !intents.isEmpty() || !metricTerms.isEmpty();
    }

    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
