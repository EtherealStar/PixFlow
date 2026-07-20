package com.pixflow.module.rubrics.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.verifier.VerdictReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class RubricsMetricsTest {
    @Test
    void recordsBoundedCriterionTagsWithoutSubjectContent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RubricsMetrics metrics = new RubricsMetrics(registry);

        metrics.recordCriterion(
                "background-clean", CriterionVerdict.INCONCLUSIVE,
                VerdictReason.PARSER_FAILURE, 0.5);

        assertThat(registry.get("pixflow.rubrics.criterion.verdict").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("pixflow.rubrics.criterion.failure").counter().count())
                .isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneMatch(tag ->
                        tag.getKey().contains("subject") || tag.getKey().contains("prompt")));
    }
}
