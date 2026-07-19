package com.pixflow.module.rubrics.api;

import com.pixflow.module.rubrics.model.QualityGate;
import com.pixflow.module.rubrics.model.SubjectType;

public record EvaluationRunItemView(
        SubjectType subjectType,
        String subjectId,
        String subjectSnapshotHash,
        EvaluationRunStatus status,
        QualityGate qualityGate,
        Double passRate,
        Double coverage,
        boolean replayable,
        String replayErrorCode) {
}
