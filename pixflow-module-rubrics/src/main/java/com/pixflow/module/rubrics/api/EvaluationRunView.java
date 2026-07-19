package com.pixflow.module.rubrics.api;

import com.pixflow.module.rubrics.model.SubjectType;
import java.util.List;

public record EvaluationRunView(
        EvaluationRunId id,
        TemplateRef template,
        String templateHash,
        SubjectType subjectType,
        String evaluatorVersion,
        String datasetId,
        String datasetVersion,
        RunPurpose purpose,
        EvaluationRunId baselineRunId,
        EvaluationRunStatus status,
        int totalCount,
        int succeededCount,
        int partialCount,
        int failedCount,
        List<EvaluationRunItemView> items,
        boolean itemsTruncated,
        EvaluationRunReport report) {

    public EvaluationRunView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
