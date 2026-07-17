package com.pixflow.module.rubrics.api;

import com.pixflow.module.rubrics.judge.JudgeRollout;
import com.pixflow.module.rubrics.model.CriterionKind;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.verifier.VerdictReason;
import java.util.List;

public record CriterionResultView(String criterionKey, CriterionKind kind, CriterionVerdict verdict,
                                  VerdictReason reason, String rationale, List<String> evidenceIds,
                                  Double agreement, List<JudgeRollout> rollouts) {
    public CriterionResultView { evidenceIds = List.copyOf(evidenceIds); rollouts = List.copyOf(rollouts); }
}
