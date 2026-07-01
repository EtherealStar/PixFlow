package com.pixflow.module.rubrics.rule;

public interface RuleVerifier {
    String dimensionKey();

    RuleCheckResult verify(RuleCheckInput input);
}
