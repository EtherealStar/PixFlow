package com.pixflow.module.memory.insight;

import com.pixflow.module.memory.recall.InsightFilter;

public interface InsightRecallService {
    InsightRecallResult recall(String query, InsightFilter filter, int topN);
}
