package com.pixflow.module.rubrics.run;
import com.pixflow.module.rubrics.judge.JudgeRollout; import com.pixflow.module.rubrics.model.CriterionKind; import com.pixflow.module.rubrics.verifier.CriterionResult; import java.util.List;
public record EvaluatedCriterion(String key, CriterionKind kind, CriterionResult result, Double agreement, List<JudgeRollout> rollouts) { public EvaluatedCriterion { rollouts=List.copyOf(rollouts); } }
