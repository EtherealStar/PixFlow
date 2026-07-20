package com.pixflow.module.memory.insight;

import com.pixflow.module.memory.recall.InsightFilter;
import java.time.Instant;

public interface InsightRecallService {
    InsightRecallResult recall(String query, InsightFilter filter, int topN, Instant asOf);
}
