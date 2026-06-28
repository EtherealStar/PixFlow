package com.pixflow.module.memory.skuhistory;

import com.pixflow.module.memory.recall.MemoryItem;
import java.util.List;

public class NoopSkuHistoryService implements SkuHistoryService {
    @Override
    public List<MemoryItem> recallBySkuIds(List<String> skuIds, int maxItemsPerSku) {
        return List.of();
    }
}
