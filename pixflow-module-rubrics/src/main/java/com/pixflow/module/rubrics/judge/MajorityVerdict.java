package com.pixflow.module.rubrics.judge;

import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.verifier.VerdictReason;

public record MajorityVerdict(CriterionVerdict verdict, VerdictReason reason, double agreement) {
}
