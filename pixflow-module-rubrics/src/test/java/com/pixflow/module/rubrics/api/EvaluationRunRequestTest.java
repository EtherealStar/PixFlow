package com.pixflow.module.rubrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.module.rubrics.model.SubjectType;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationRunRequestTest {

    @Test
    void explicitSubjectsKeepFirstOccurrenceOrder() {
        ExplicitSubjects selection = new ExplicitSubjects(
                SubjectType.IMAGE_RESULT,
                List.of("result-2", "result-1", "result-2"));

        assertThat(selection.subjectIds()).containsExactly("result-2", "result-1");
    }

    @Test
    void datasetSelectionRequiresACompleteIdentity() {
        assertThatThrownBy(() -> new DatasetSelection("dataset-a", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataset version");
    }

    @Test
    void formalRegressionRequiresFrozenBaselineRun() {
        assertThatThrownBy(() -> new EvaluationRunRequest(
                new TemplateRef("image-quality", "2.0.0"),
                RunPurpose.FORMAL_REGRESSION,
                RunTrigger.MANUAL,
                new DatasetSelection("gold-images", "1.0.0"),
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseline");
    }
}
