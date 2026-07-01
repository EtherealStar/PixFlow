package com.pixflow.module.rubrics.template;

import java.math.BigDecimal;
import java.util.List;

public record RubricDomain(
        String key,
        String name,
        BigDecimal weight,
        List<RubricDimension> dimensions) {

    public RubricDomain {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("domain key must not be blank");
        }
        if (weight == null) {
            weight = BigDecimal.ONE;
        }
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    }
}
