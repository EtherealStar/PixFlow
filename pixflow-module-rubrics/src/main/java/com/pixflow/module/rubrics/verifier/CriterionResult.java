package com.pixflow.module.rubrics.verifier;

import com.pixflow.module.rubrics.model.CriterionVerdict;
import java.util.List;
import java.util.Map;

public record CriterionResult(CriterionVerdict verdict, VerdictReason reason, String rationale,
                              List<String> evidenceIds, Map<String, Object> diagnostics) {
    public CriterionResult {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
