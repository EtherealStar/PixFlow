package com.pixflow.module.rubrics.summary;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.rubrics.model.CriterionKind;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.model.QualityGate;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationSummaryCalculatorTest {
    private final EvaluationSummaryCalculator calculator = new EvaluationSummaryCalculator();

    @Test
    void separatesQualityFromEvaluatorAvailability() {
        EvaluationSummary summary = calculator.calculate(List.of(
                outcome("resolution", CriterionKind.HARD_RULE, CriterionVerdict.PASS),
                outcome("format", CriterionKind.HARD_RULE, CriterionVerdict.INCONCLUSIVE),
                outcome("clean", CriterionKind.PRINCIPLE, CriterionVerdict.PASS),
                outcome("visible", CriterionKind.PRINCIPLE, CriterionVerdict.FAIL)));

        assertThat(summary.qualityGate()).isEqualTo(QualityGate.UNKNOWN);
        assertThat(summary.passRate()).isEqualTo(0.5d);
        assertThat(summary.coverage()).isEqualTo(0.75d);
    }

    @Test
    void usesNullForEmptyDenominators() {
        EvaluationSummary summary = calculator.calculate(List.of(
                outcome("clean", CriterionKind.PRINCIPLE, CriterionVerdict.NOT_APPLICABLE)));

        assertThat(summary.qualityGate()).isEqualTo(QualityGate.PASSED);
        assertThat(summary.applicableHardRuleCount()).isZero();
        assertThat(summary.passRate()).isNull();
        assertThat(summary.coverage()).isNull();
    }

    private static CriterionOutcome outcome(String key, CriterionKind kind, CriterionVerdict verdict) {
        return new CriterionOutcome(key, kind, verdict);
    }
}
