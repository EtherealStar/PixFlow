package com.pixflow.module.memory.insight;

import com.pixflow.module.memory.recall.InsightFilter;
import com.pixflow.module.memory.recall.MemoryItem;
import java.util.List;

public interface InsightKeywordSearch {
    List<MemoryItem> search(String query, InsightFilter filter, int topK);
}
