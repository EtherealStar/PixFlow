package com.pixflow.module.memory.recall;

import java.util.List;
import java.util.Map;

public record InsightFilter(
        List<String> skuIds,
        List<String> categories,
        double minConfidence,
        Map<String, Object> attributes) {

    public InsightFilter {
        skuIds = normalize(skuIds);
        categories = normalize(categories);
        minConfidence = Double.isFinite(minConfidence) && minConfidence > 0 ? minConfidence : 0;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static InsightFilter empty() {
        return new InsightFilter(List.of(), List.of(), 0, Map.of());
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
