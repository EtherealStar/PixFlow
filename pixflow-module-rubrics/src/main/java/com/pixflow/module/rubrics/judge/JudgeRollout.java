package com.pixflow.module.rubrics.judge;

import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.verifier.VerdictReason;
import java.util.List;

public record JudgeRollout(int index, CriterionVerdict verdict, VerdictReason reason,
                           String rationale, List<String> evidenceIds, String provider,
                           String model, String promptHash, long latencyMs,
                           long promptTokens, long completionTokens, long totalTokens) {
    public JudgeRollout {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
