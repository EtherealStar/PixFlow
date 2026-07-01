package com.pixflow.module.rubrics.score;

import com.pixflow.module.rubrics.model.Confidence;
import com.pixflow.module.rubrics.model.Verdict;
import com.pixflow.module.rubrics.template.RubricDimension;
import com.pixflow.module.rubrics.template.RubricDomain;
import com.pixflow.module.rubrics.template.RubricTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ScoreAggregator {
    public RubricScore aggregate(RubricTemplate template, List<DimensionScore> dimensionScores) {
        Map<String, DimensionScore> byDimension = dimensionScores.stream()
                .collect(Collectors.toMap(DimensionScore::dimensionKey, Function.identity(), (left, right) -> right));
        List<DomainScore> domainScores = template.domains().stream()
                .map(domain -> aggregateDomain(domain, byDimension))
                .toList();
        BigDecimal overall = weightedDomainAverage(template.domains(), domainScores);
        return new RubricScore(
                overall,
                findDomainScore(domainScores, "IMAGE_QUALITY"),
                findDomainScore(domainScores, "COPY_QUALITY"),
                findDomainScore(domainScores, "DECISION_QUALITY"),
                domainScores,
                dimensionScores);
    }

    public DimensionScore withProgramScore(DimensionScore source) {
        BigDecimal mapped = mappedScore(source.verdict(), source.confidence());
        return new DimensionScore(
                source.domainKey(),
                source.dimensionKey(),
                source.verdict(),
                source.confidence(),
                mapped,
                source.rationale(),
                source.evidence(),
                source.numericMetric());
    }

    private DomainScore aggregateDomain(RubricDomain domain, Map<String, DimensionScore> byDimension) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        for (RubricDimension dimension : domain.dimensions()) {
            DimensionScore score = byDimension.get(dimension.key());
            if (score == null || score.score() == null) {
                continue;
            }
            BigDecimal effectiveWeight = effectiveWeight(dimension.weight(), score.confidence());
            weightedSum = weightedSum.add(score.score().multiply(effectiveWeight));
            weightSum = weightSum.add(effectiveWeight);
        }
        BigDecimal score = weightSum.signum() == 0
                ? null
                : weightedSum.divide(weightSum, 2, RoundingMode.HALF_UP);
        return new DomainScore(domain.key(), score, byDimension.values().stream()
                .filter(dimension -> dimension.domainKey().equals(domain.key()))
                .toList());
    }

    private static BigDecimal weightedDomainAverage(List<RubricDomain> domains, List<DomainScore> scores) {
        Map<String, DomainScore> byDomain = scores.stream()
                .collect(Collectors.toMap(DomainScore::domainKey, Function.identity()));
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        for (RubricDomain domain : domains) {
            DomainScore score = byDomain.get(domain.key());
            if (score == null || score.score() == null) {
                continue;
            }
            weightedSum = weightedSum.add(score.score().multiply(domain.weight()));
            weightSum = weightSum.add(domain.weight());
        }
        return weightSum.signum() == 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : weightedSum.divide(weightSum, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal findDomainScore(List<DomainScore> domainScores, String key) {
        return domainScores.stream()
                .filter(score -> score.domainKey().equals(key))
                .map(DomainScore::score)
                .findFirst()
                .orElse(null);
    }

    private static BigDecimal effectiveWeight(BigDecimal weight, Confidence confidence) {
        // LOW confidence is intentionally retained but discounted so weak evidence remains visible in reports.
        if (confidence == Confidence.LOW) {
            return weight.multiply(new BigDecimal("0.5"));
        }
        return weight;
    }

    private static BigDecimal mappedScore(Verdict verdict, Confidence confidence) {
        return switch (verdict) {
            case PASS -> switch (confidence) {
                case HIGH -> new BigDecimal("100");
                case MEDIUM -> new BigDecimal("80");
                case LOW -> new BigDecimal("60");
            };
            case FAIL -> switch (confidence) {
                case HIGH -> BigDecimal.ZERO;
                case MEDIUM -> new BigDecimal("20");
                case LOW -> new BigDecimal("40");
            };
        };
    }
}
