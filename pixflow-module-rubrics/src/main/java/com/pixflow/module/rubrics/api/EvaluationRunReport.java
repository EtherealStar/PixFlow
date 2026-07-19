package com.pixflow.module.rubrics.api;

import java.util.Map;

public record EvaluationRunReport(RunPurpose purpose, Map<String, Object> facts, boolean complete) {

    public EvaluationRunReport {
        if (purpose == null) {
            throw new IllegalArgumentException("report purpose is required");
        }
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }
}
