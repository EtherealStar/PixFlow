package com.pixflow.module.rubrics.baseline;

import java.math.BigDecimal;

public record DimensionDelta(String dimensionKey, BigDecimal baselineScore, BigDecimal currentScore, BigDecimal delta, boolean degraded) {
}
