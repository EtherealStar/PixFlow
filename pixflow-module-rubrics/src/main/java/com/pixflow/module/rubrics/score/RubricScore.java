package com.pixflow.module.rubrics.score;

import java.math.BigDecimal;
import java.util.List;

public record RubricScore(
        BigDecimal overallScore,
        BigDecimal imageScore,
        BigDecimal copyScore,
        BigDecimal decisionScore,
        List<DomainScore> domainScores,
        List<DimensionScore> dimensionScores) {

    public RubricScore {
        domainScores = domainScores == null ? List.of() : List.copyOf(domainScores);
        dimensionScores = dimensionScores == null ? List.of() : List.copyOf(dimensionScores);
    }
}
