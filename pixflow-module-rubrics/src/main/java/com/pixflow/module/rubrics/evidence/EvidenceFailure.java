package com.pixflow.module.rubrics.evidence;

public record EvidenceFailure(EvidenceFailureKind kind, String code) {

    public EvidenceFailure {
        if (kind == null) {
            throw new IllegalArgumentException("evidence failure kind is required");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("evidence failure code must not be blank");
        }
    }
}
