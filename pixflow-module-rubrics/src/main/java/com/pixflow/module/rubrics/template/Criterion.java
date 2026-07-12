package com.pixflow.module.rubrics.template;

import com.pixflow.module.rubrics.model.CriterionKind;
import com.pixflow.module.rubrics.model.EvidenceType;
import java.util.Set;

public record Criterion(
        String key,
        CriterionKind kind,
        String statement,
        String passAnchor,
        String failAnchor,
        Set<EvidenceType> evidenceTypes,
        Applicability applicability,
        VerifierSpec verifier) {
    public Criterion {
        evidenceTypes = evidenceTypes == null ? Set.of() : Set.copyOf(evidenceTypes);
    }
}
