package com.pixflow.module.memory.skuhistory;

import com.pixflow.module.memory.recall.MemoryItem;
import java.util.List;

public interface SkuHistoryService {
    List<MemoryItem> recallBySkuIds(List<String> skuIds, int maxItemsPerSku);

    void appendRubricsScore(SkuHistoryRubricsScoreCommand command);
}
