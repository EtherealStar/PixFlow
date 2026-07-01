package com.pixflow.module.memory.skuhistory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.module.memory.recall.MemoryItem;
import com.pixflow.module.memory.recall.MemoryType;
import java.time.Instant;
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
                        rubricsImportance(history),
                        1.0,
                        history.getCreatedAt(),
                        history.getCreatedAt(),
                        Map.of("task_id", valueOrEmpty(history.getTaskId()))))
                .toList();
    }

    @Override
    public void appendRubricsScore(SkuHistoryRubricsScoreCommand command) {
        Objects.requireNonNull(command, "command");
        SkuHistory history = new SkuHistory();
        history.setSkuId(command.skuId());
        history.setTaskId(command.taskId());
        history.setParamsJson(command.paramsJson());
        history.setRubricsScore(command.rubricsScore());
        history.setMetricsAfter(renderEvidence(command.evidence()));
        history.setCreatedAt(Instant.now());
        mapper.insert(history);
    }

    private static String render(SkuHistory history) {
        return "SKU " + history.getSkuId() + " 历史处理 task=" + valueOrEmpty(history.getTaskId())
                + " params=" + valueOrEmpty(history.getParamsJson());
    }

    private static double rubricsImportance(SkuHistory history) {
        if (history.getRubricsScore() == null) {
            return 0.5;
        }
        return Math.min(1.0, Math.max(0.0, history.getRubricsScore().doubleValue() / 100.0));
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String renderEvidence(Map<String, Object> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "{}";
        }
        return evidence.toString();
    }
}
