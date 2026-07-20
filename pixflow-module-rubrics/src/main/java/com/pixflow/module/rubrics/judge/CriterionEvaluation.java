package com.pixflow.module.rubrics.judge;

import com.pixflow.module.rubrics.verifier.CriterionResult;
import java.util.List;

public record CriterionEvaluation(CriterionResult result, List<JudgeRollout> rollouts,
                                  double agreement, String evaluatorVersion) {
    public CriterionEvaluation {
        rollouts = List.copyOf(rollouts);
    }
}
