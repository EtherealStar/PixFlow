package com.pixflow.module.rubrics.run;

import com.pixflow.module.rubrics.subject.EvaluationSubject;
import java.util.List;

public final class SelfJudgedDetector {
    public boolean detect(EvaluationSubject subject, List<EvaluatedCriterion> criteria) {
        return subject.productionModel().map(producer -> criteria.stream()
                .flatMap(criterion -> criterion.rollouts().stream())
                .anyMatch(rollout -> producer.provider().equalsIgnoreCase(rollout.provider())
                        && producer.model().equalsIgnoreCase(rollout.model())))
                .orElse(false);
    }
}
