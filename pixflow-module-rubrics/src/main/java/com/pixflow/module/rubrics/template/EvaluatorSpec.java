package com.pixflow.module.rubrics.template;

import com.pixflow.infra.ai.model.ModelRole;

public record EvaluatorSpec(ModelRole judgeRole, int rollouts, String parserSchemaVersion) {
    public EvaluatorSpec {
        if (rollouts <= 0 || rollouts % 2 == 0) {
            throw new IllegalArgumentException("rollouts must be a positive odd number");
        }
        if (parserSchemaVersion == null || parserSchemaVersion.isBlank()) {
            throw new IllegalArgumentException("parserSchemaVersion must not be blank");
        }
    }
}
