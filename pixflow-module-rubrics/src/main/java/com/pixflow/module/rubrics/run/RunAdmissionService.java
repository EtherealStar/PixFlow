package com.pixflow.module.rubrics.run;

import com.pixflow.module.rubrics.api.EvaluationRunId;
import com.pixflow.module.rubrics.api.EvaluationRunRequest;

/** Rubrics 内部自动化入口：只持久化 admission facts，不执行评估。 */
public final class RunAdmissionService {
    private final DefaultRubricsEvaluationService evaluations;

    public RunAdmissionService(DefaultRubricsEvaluationService evaluations) {
        this.evaluations = evaluations;
    }

    public EvaluationRunId admit(EvaluationRunRequest request, String admissionKey) {
        return evaluations.admit(request, admissionKey);
    }
}
