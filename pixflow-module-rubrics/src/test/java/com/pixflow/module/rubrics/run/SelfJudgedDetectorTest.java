package com.pixflow.module.rubrics.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.rubrics.judge.JudgeRollout;
import com.pixflow.module.rubrics.model.CriterionKind;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.model.SubjectType;
import com.pixflow.module.rubrics.subject.EvaluationSubject;
import com.pixflow.module.rubrics.verifier.CriterionResult;
import com.pixflow.module.rubrics.verifier.VerdictReason;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SelfJudgedDetectorTest {
    @Test
    void detectsMatchingProducerAndJudgeIdentity() {
        EvaluationSubject subject = new EvaluationSubject() {
            public SubjectType type() { return SubjectType.IMAGE_RESULT; }
            public String id() { return "1"; }
            public String snapshotHash() { return "hash"; }
            public Optional<ProductionModelIdentity> productionModel() {
                return Optional.of(new ProductionModelIdentity("provider", "model-v1"));
            }
        };
        var result = new CriterionResult(CriterionVerdict.PASS, VerdictReason.RULE_MATCH,
                "reason", List.of("E1"), Map.of());
        var rollout = new JudgeRollout(1, CriterionVerdict.PASS, VerdictReason.RULE_MATCH,
                "reason", List.of("E1"), "provider", "model-v1", "hash", 1, 1, 1, 2);

        assertThat(new SelfJudgedDetector().detect(subject,
                List.of(new EvaluatedCriterion("key", CriterionKind.PRINCIPLE, result, 1.0, List.of(rollout)))))
                .isTrue();
    }
}
