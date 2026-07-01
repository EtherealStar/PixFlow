package com.pixflow.module.rubrics.baseline;

import java.math.BigDecimal;
import java.util.List;

public record RegressionReport(
        long currentRunId,
        long baselineRunId,
        BigDecimal overallDelta,
        String trend,
        List<DimensionDelta> dimensions) {

    public RegressionReport {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    }
}
