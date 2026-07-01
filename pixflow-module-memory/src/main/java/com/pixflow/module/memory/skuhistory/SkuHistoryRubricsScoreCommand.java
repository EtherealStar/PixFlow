package com.pixflow.module.memory.skuhistory;

import java.math.BigDecimal;
import java.util.Map;

public record SkuHistoryRubricsScoreCommand(
        String skuId,
        String taskId,
        BigDecimal rubricsScore,
        String paramsJson,
        Map<String, Object> evidence) {

    public SkuHistoryRubricsScoreCommand {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId must not be blank");
        }
        if (rubricsScore == null) {
            throw new IllegalArgumentException("rubricsScore must not be null");
        }
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}
