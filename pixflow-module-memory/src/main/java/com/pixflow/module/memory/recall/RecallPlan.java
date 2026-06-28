package com.pixflow.module.memory.recall;

import java.util.List;
import java.util.Map;

public record RecallPlan(
        boolean recallPreference,
        List<String> skuIds,
        boolean recallSkuHistory,
        boolean recallInsight,
        String insightQuery,
        InsightFilter insightFilter,
        int preferenceMaxItems,
        int skuHistoryMaxItemsPerSku,
        int insightTopN,
        Map<String, Object> trace) {

    public RecallPlan {
        skuIds = skuIds == null ? List.of() : List.copyOf(skuIds);
        insightQuery = insightQuery == null ? "" : insightQuery;
        insightFilter = insightFilter == null ? InsightFilter.empty() : insightFilter;
        trace = trace == null ? Map.of() : Map.copyOf(trace);
    }
}
