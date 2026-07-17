package com.pixflow.module.rubrics.api;

import com.pixflow.module.rubrics.model.SubjectType;
import com.pixflow.module.rubrics.run.RunTriggerType;
import java.util.List;

public interface RubricsEvaluationService {
    RubricsRunView start(RunEvaluationCommand command);
    RubricsRunView start(RunEvaluationCommand command, RunTriggerType triggerType);
    RubricsRunView resume(long runId);
    RubricsRunView getRun(long runId);
    EvaluationView getEvaluation(long evaluationId);
    List<EvaluationSummaryView> history(SubjectType type, String subjectId);
}
