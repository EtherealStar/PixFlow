package com.pixflow.module.rubrics.summary;

import com.pixflow.module.rubrics.model.CriterionKind;
import com.pixflow.module.rubrics.model.CriterionVerdict;

public record CriterionOutcome(String criterionKey, CriterionKind kind, CriterionVerdict verdict) {
}
