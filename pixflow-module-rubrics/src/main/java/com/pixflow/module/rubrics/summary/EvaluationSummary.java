package com.pixflow.module.rubrics.summary;

import com.pixflow.module.rubrics.model.QualityGate;

public record EvaluationSummary(
        QualityGate qualityGate,
        Double passRate,
        Double coverage,
        int applicableHardRuleCount,
        int passCount,
        int failCount,
        int inconclusiveCount,
        int notApplicableCount) {
}
