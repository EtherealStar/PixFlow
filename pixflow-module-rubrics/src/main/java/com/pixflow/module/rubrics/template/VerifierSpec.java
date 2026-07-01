package com.pixflow.module.rubrics.template;

import java.util.Map;

public record VerifierSpec(VerifierType type, String ruleClass, String prompt, Map<String, Object> params) {
    public VerifierSpec {
        type = type == null ? VerifierType.LLM : type;
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
