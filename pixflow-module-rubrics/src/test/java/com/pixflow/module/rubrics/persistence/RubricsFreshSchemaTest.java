package com.pixflow.module.rubrics.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RubricsFreshSchemaTest {

    @Test
    void baselineContainsOnlyEvidenceGroundedEvaluationFacts() throws Exception {
        String schema;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V1__create_rubrics_evaluation_facts.sql")) {
            assertThat(stream).isNotNull();
            schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(schema)
                .contains("claim_epoch", "lease_expires_at", "rubrics_dataset", "rubrics_gold_label")
                .doesNotContain("rubrics_score", "rubrics_promotion", "overall_score");
    }
}
