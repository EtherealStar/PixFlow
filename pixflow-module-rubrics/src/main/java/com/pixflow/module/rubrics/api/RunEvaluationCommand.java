package com.pixflow.module.rubrics.api;

import com.pixflow.module.rubrics.model.SubjectType;
import java.util.List;

public record RunEvaluationCommand(String templateId, String templateVersion, SubjectType subjectType,
                                   String datasetId, String datasetVersion, List<String> subjectIds) {
    public RunEvaluationCommand { subjectIds = subjectIds == null ? List.of() : List.copyOf(subjectIds); }
}
