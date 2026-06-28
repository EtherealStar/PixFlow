package com.pixflow.module.memory.insight;

import com.pixflow.module.memory.recall.InsightFilter;

public class NoopInsightRecallService implements InsightRecallService {
    @Override
    public InsightRecallResult recall(String query, InsightFilter filter, int topN) {
        return InsightRecallResult.empty();
    }
}
