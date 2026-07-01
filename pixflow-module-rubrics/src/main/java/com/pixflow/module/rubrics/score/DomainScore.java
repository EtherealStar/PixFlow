package com.pixflow.module.rubrics.score;

import java.math.BigDecimal;
import java.util.List;

public record DomainScore(String domainKey, BigDecimal score, List<DimensionScore> dimensions) {
    public DomainScore {
        if (domainKey == null || domainKey.isBlank()) {
            throw new IllegalArgumentException("domainKey must not be blank");
        }
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    }
}
