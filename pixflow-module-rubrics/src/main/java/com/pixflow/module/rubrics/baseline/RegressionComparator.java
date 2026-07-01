package com.pixflow.module.rubrics.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.rubrics.config.RubricsProperties;
import com.pixflow.module.rubrics.persistence.RubricsScoreEntity;
import com.pixflow.module.rubrics.persistence.RubricsScoreMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RegressionComparator {
    private final RubricsScoreMapper scoreMapper;
    private final ObjectMapper objectMapper;
    private final RubricsProperties properties;

    public RegressionComparator(RubricsScoreMapper scoreMapper, ObjectMapper objectMapper, RubricsProperties properties) {
        this.scoreMapper = Objects.requireNonNull(scoreMapper, "scoreMapper");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public RegressionReport compare(long currentRunId, long baselineRunId) {
        List<RubricsScoreEntity> current = scoreMapper.findByRunId(currentRunId);
        List<RubricsScoreEntity> baseline = scoreMapper.findByRunId(baselineRunId);
        BigDecimal currentOverall = averageOverall(current);
        BigDecimal baselineOverall = averageOverall(baseline);
        BigDecimal overallDelta = currentOverall.subtract(baselineOverall).setScale(2, RoundingMode.HALF_UP);
        Map<String, BigDecimal> currentDims = averageDimensions(current);
        Map<String, BigDecimal> baselineDims = averageDimensions(baseline);
        List<DimensionDelta> deltas = currentDims.entrySet().stream()
                .filter(entry -> baselineDims.containsKey(entry.getKey()))
                .map(entry -> {
                    BigDecimal base = baselineDims.get(entry.getKey());
                    BigDecimal now = entry.getValue();
                    BigDecimal delta = now.subtract(base).setScale(2, RoundingMode.HALF_UP);
                    boolean degraded = delta.doubleValue() < properties.getBaseline().getRegressionDimensionThreshold();
                    return new DimensionDelta(entry.getKey(), base, now, delta, degraded);
                })
                .toList();
        String trend = overallDelta.doubleValue() < properties.getBaseline().getRegressionOverallThreshold()
                ? "DOWN"
                : (overallDelta.doubleValue() > Math.abs(properties.getBaseline().getRegressionDimensionThreshold()) ? "UP" : "STABLE");
        return new RegressionReport(currentRunId, baselineRunId, overallDelta, trend, deltas);
    }

    private static BigDecimal averageOverall(List<RubricsScoreEntity> scores) {
        if (scores.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal sum = scores.stream()
                .map(RubricsScoreEntity::getOverallScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(scores.size()), 2, RoundingMode.HALF_UP);
    }

    private Map<String, BigDecimal> averageDimensions(List<RubricsScoreEntity> scores) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RubricsScoreEntity score : scores) {
            try {
                JsonNode root = objectMapper.readTree(score.getDimensionScoresJson());
                if (!root.isArray()) {
                    continue;
                }
                for (JsonNode node : root) {
                    String key = node.path("dimensionKey").asText("");
                    if (key.isBlank() || !node.hasNonNull("score")) {
                        continue;
                    }
                    sums.merge(key, node.path("score").decimalValue(), BigDecimal::add);
                    counts.merge(key, 1, Integer::sum);
                }
            } catch (Exception ignored) {
                // Malformed historical detail is ignored so run-level regression remains available.
            }
        }
        Map<String, BigDecimal> averages = new LinkedHashMap<>();
        sums.forEach((key, value) -> averages.put(key, value.divide(new BigDecimal(counts.get(key)), 2, RoundingMode.HALF_UP)));
        return averages;
    }
}
