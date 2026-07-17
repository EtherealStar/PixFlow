package com.pixflow.module.rubrics.judge;

import static org.assertj.core.api.Assertions.assertThat;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.verifier.VerdictReason;
import java.util.List;
import org.junit.jupiter.api.Test;

class MajorityVerdictReducerTest {
    private final MajorityVerdictReducer reducer = new MajorityVerdictReducer();

    @Test void strictMajorityWinsAcrossAllConfiguredRollouts() {
        var result = reducer.reduce(List.of(rollout(1, CriterionVerdict.PASS),
                rollout(2, CriterionVerdict.PASS), rollout(3, CriterionVerdict.FAIL)), 3);
        assertThat(result.verdict()).isEqualTo(CriterionVerdict.PASS);
        assertThat(result.agreement()).isEqualTo(2.0 / 3.0);
    }

    @Test void evaluatorFailureRemainsInDenominatorAndPreventsFalseMajority() {
        var result = reducer.reduce(List.of(rollout(1, CriterionVerdict.PASS),
                rollout(2, CriterionVerdict.FAIL), rollout(3, CriterionVerdict.INCONCLUSIVE)), 3);
        assertThat(result.verdict()).isEqualTo(CriterionVerdict.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(VerdictReason.JUDGE_DISAGREEMENT);
        assertThat(result.agreement()).isEqualTo(1.0 / 3.0);
    }

    private JudgeRollout rollout(int index, CriterionVerdict verdict) {
        return new JudgeRollout(index, verdict, VerdictReason.RULE_MATCH, "because E1", List.of("E1"),
                "fake", "judge-v1", "hash", 1, 1, 1, 2);
    }
}
