package com.pixflow.module.memory.skuhistory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.module.memory.recall.MemoryItem;
import com.pixflow.module.memory.recall.MemoryType;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MybatisSkuHistoryService implements SkuHistoryService {
    private final SkuHistoryMapper mapper;

    public MybatisSkuHistoryService(SkuHistoryMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public List<MemoryItem> recallBySkuIds(List<String> skuIds, int maxItemsPerSku) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, maxItemsPerSku) * skuIds.size();
        return mapper.selectList(new LambdaQueryWrapper<SkuHistory>()
                        .in(SkuHistory::getSkuId, skuIds)
                        .orderByDesc(SkuHistory::getCreatedAt)
                        .last("LIMIT " + limit))
                .stream()
                .map(history -> new MemoryItem(
                        "sku_history:" + history.getId(),
                        MemoryType.SKU_HISTORY,
                        render(history),
                        "sku_history",
                        "",
                        history.getSkuId(),
                        1.0,
                        0,
                        1.0,
                        0.5,
                        1.0,
                        history.getCreatedAt(),
                        history.getCreatedAt(),
                        Map.of("task_id", valueOrEmpty(history.getTaskId()))))
                .toList();
    }

    private static String render(SkuHistory history) {
        return "SKU " + history.getSkuId() + " 历史处理 task=" + valueOrEmpty(history.getTaskId())
                + " params=" + valueOrEmpty(history.getParamsJson());
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

}
