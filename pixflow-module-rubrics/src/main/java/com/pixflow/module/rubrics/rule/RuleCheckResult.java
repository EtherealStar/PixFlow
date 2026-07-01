package com.pixflow.module.rubrics.rule;

import com.pixflow.module.rubrics.model.Confidence;
import com.pixflow.module.rubrics.model.EvidenceRef;
import com.pixflow.module.rubrics.model.Verdict;
import java.util.List;
import java.util.OptionalDouble;

public record RuleCheckResult(
        Verdict verdict,
        Confidence confidence,
        String rationale,
        List<EvidenceRef> evidence,
        OptionalDouble numericMetric) {

    public RuleCheckResult {
        if (verdict == null) {
            throw new IllegalArgumentException("verdict must not be null");
        }
        confidence = confidence == null ? Confidence.HIGH : confidence;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        numericMetric = numericMetric == null ? OptionalDouble.empty() : numericMetric;
    }

    public static RuleCheckResult pass(String rationale, List<EvidenceRef> evidence) {
        return new RuleCheckResult(Verdict.PASS, Confidence.HIGH, rationale, evidence, OptionalDouble.empty());
    }

    public static RuleCheckResult fail(String rationale, List<EvidenceRef> evidence) {
        return new RuleCheckResult(Verdict.FAIL, Confidence.HIGH, rationale, evidence, OptionalDouble.empty());
    }
}
