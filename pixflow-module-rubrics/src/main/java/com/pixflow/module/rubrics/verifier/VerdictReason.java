package com.pixflow.module.rubrics.verifier;

public enum VerdictReason {
    RULE_MATCH,
    RULE_MISMATCH,
    MISSING_EVIDENCE,
    EVALUATOR_FAILURE,
    INVALID_EVIDENCE,
    PARSER_FAILURE,
    JUDGE_DISAGREEMENT,
    NOT_APPLICABLE
}
