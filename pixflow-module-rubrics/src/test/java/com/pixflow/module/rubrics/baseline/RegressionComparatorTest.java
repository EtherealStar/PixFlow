package com.pixflow.module.rubrics.baseline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.rubrics.config.RubricsProperties;
import com.pixflow.module.rubrics.persistence.RubricsScoreEntity;
import com.pixflow.module.rubrics.persistence.RubricsScoreMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegressionComparatorTest {
    @Test
    void detectsDownwardOverallTrend() {
        RubricsScoreMapper mapper = mock(RubricsScoreMapper.class);
        when(mapper.findByRunId(1L)).thenReturn(List.of(score(1L, "90", "100")));
        when(mapper.findByRunId(2L)).thenReturn(List.of(score(2L, "70", "70")));
        RegressionComparator comparator = new RegressionComparator(mapper, new ObjectMapper(), new RubricsProperties());

        RegressionReport report = comparator.compare(2L, 1L);

        assertThat(report.trend()).isEqualTo("DOWN");
        assertThat(report.overallDelta()).isEqualByComparingTo("-20.00");
        assertThat(report.dimensions()).anyMatch(DimensionDelta::degraded);
    }

    private static RubricsScoreEntity score(long runId, String overall, String dimensionScore) {
        RubricsScoreEntity score = new RubricsScoreEntity();
        score.setRunId(runId);
        score.setOverallScore(new BigDecimal(overall));
        score.setDimensionScoresJson("[{\"dimensionKey\":\"resolution\",\"score\":" + dimensionScore + "}]");
        return score;
    }
}
