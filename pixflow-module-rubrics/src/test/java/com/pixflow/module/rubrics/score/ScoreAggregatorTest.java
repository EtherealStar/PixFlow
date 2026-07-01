package com.pixflow.module.rubrics.score;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.rubrics.model.Confidence;
import com.pixflow.module.rubrics.model.Verdict;
import com.pixflow.module.rubrics.template.RubricDimension;
import com.pixflow.module.rubrics.template.RubricDomain;
import com.pixflow.module.rubrics.template.RubricTemplate;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoreAggregatorTest {
    @Test
    void mapsBinaryVerdictAndAggregatesByWeights() {
        ScoreAggregator aggregator = new ScoreAggregator();
        RubricTemplate template = new RubricTemplate("default", "1.0", "Default", List.of(
                new RubricDomain("IMAGE_QUALITY", "Image", new BigDecimal("0.6"), List.of(
                        dimension("resolution", "0.25"),
                        dimension("format", "0.75"))),
                new RubricDomain("COPY_QUALITY", "Copy", new BigDecimal("0.4"), List.of(
                        dimension("fluency", "1.0")))));

        List<DimensionScore> dimensions = List.of(
                aggregator.withProgramScore(score("IMAGE_QUALITY", "resolution", Verdict.PASS, Confidence.HIGH)),
                aggregator.withProgramScore(score("IMAGE_QUALITY", "format", Verdict.FAIL, Confidence.MEDIUM)),
                aggregator.withProgramScore(score("COPY_QUALITY", "fluency", Verdict.PASS, Confidence.LOW)));

        RubricScore result = aggregator.aggregate(template, dimensions);

        assertThat(result.imageScore()).isEqualByComparingTo("40.00");
        assertThat(result.copyScore()).isEqualByComparingTo("60.00");
        assertThat(result.overallScore()).isEqualByComparingTo("48.00");
    }

    private static RubricDimension dimension(String key, String weight) {
        return new RubricDimension(key, key, new BigDecimal(weight), null, List.of());
    }

    private static DimensionScore score(String domain, String dimension, Verdict verdict, Confidence confidence) {
        return new DimensionScore(domain, dimension, verdict, confidence, null, "", List.of(), null);
    }
}
