package com.pixflow.module.memory.insight;

import com.pixflow.module.memory.recall.InsightFilter;
import com.pixflow.module.memory.recall.MemoryItem;
import java.util.List;

public interface InsightVectorRepo {
    void ensureCollection(int dimension);

    void upsertActive(AnalysisInsight insight, float[] vector);

    List<MemoryItem> search(float[] query, int topK, float threshold, InsightFilter filter);

    void delete(String insightId);
}
