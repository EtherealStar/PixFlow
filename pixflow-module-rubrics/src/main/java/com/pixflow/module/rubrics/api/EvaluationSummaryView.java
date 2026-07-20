package com.pixflow.module.rubrics.api;

import com.pixflow.module.rubrics.model.QualityGate;
import com.pixflow.module.rubrics.model.SubjectType;

public record EvaluationSummaryView(long id, long runId, SubjectType subjectType, String subjectId,
                                    QualityGate qualityGate, Double passRate, Double coverage,
                                    String templateVersion, String evaluatorVersion) {
}
