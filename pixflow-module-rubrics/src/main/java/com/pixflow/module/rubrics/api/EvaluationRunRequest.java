package com.pixflow.module.rubrics.api;

import java.util.Objects;

public record EvaluationRunRequest(
        TemplateRef template,
        RunPurpose purpose,
        RunTrigger trigger,
        RunSelection selection,
        EvaluationRunId baselineRunId) {

    public EvaluationRunRequest {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(selection, "selection");
        if (purpose == RunPurpose.FORMAL_REGRESSION && baselineRunId == null) {
            throw new IllegalArgumentException("formal regression requires a baseline run");
        }
        if (purpose != RunPurpose.FORMAL_REGRESSION && baselineRunId != null) {
            throw new IllegalArgumentException("baseline run is only valid for formal regression");
        }
        if (purpose == RunPurpose.FORMAL_REGRESSION && !(selection instanceof DatasetSelection)) {
            throw new IllegalArgumentException("formal regression requires a dataset selection");
        }
    }
}
