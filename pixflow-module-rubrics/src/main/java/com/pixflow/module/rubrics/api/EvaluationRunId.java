package com.pixflow.module.rubrics.api;

public record EvaluationRunId(long value) {

    public EvaluationRunId {
        if (value <= 0) {
            throw new IllegalArgumentException("evaluation run id must be positive");
        }
    }
}
