package com.pixflow.module.rubrics.template;

import java.math.BigDecimal;
import java.util.List;

public record RubricDimension(
        String key,
        String name,
        BigDecimal weight,
        VerifierSpec verifier,
        List<Anchor> anchors) {

    public RubricDimension {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("dimension key must not be blank");
        }
        if (weight == null) {
            weight = BigDecimal.ONE;
        }
        anchors = anchors == null ? List.of() : List.copyOf(anchors);
    }
}
