package com.pixflow.module.rubrics.api;

public interface RubricsEvaluationService {

    EvaluationRunView start(EvaluationRunRequest request);

    EvaluationRunView resume(EvaluationRunId runId);

    EvaluationRunView get(EvaluationRunId runId);
}
