package com.pixflow.module.rubrics.verifier;

import com.pixflow.module.rubrics.evidence.EvidenceEntry;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.template.Criterion;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RuleCriterionVerifier {
    public CriterionResult verify(Criterion criterion, List<EvidenceEntry> evidence) {
        EvidenceEntry metadata = evidence.stream().filter(entry -> entry.type() == EvidenceType.IMAGE_METADATA)
                .findFirst().orElse(null);
        if (metadata == null) {
            return inconclusive(VerdictReason.MISSING_EVIDENCE, "required image metadata is unavailable");
        }
        try {
            return switch (criterion.verifier().ruleClass()) {
                case "resolution" -> resolution(criterion, metadata);
                case "format" -> format(criterion, metadata);
                default -> inconclusive(VerdictReason.EVALUATOR_FAILURE, "unknown rule verifier: " + criterion.verifier().ruleClass());
            };
        } catch (RuntimeException error) {
            return inconclusive(VerdictReason.EVALUATOR_FAILURE, "rule verifier failed: " + error.getClass().getSimpleName());
        }
    }

    private CriterionResult resolution(Criterion criterion, EvidenceEntry evidence) {
        int width = number(evidence.metadata(), "width");
        int height = number(evidence.metadata(), "height");
        int minWidth = number(criterion.verifier().params(), "minWidth");
        int minHeight = number(criterion.verifier().params(), "minHeight");
        boolean pass = width >= minWidth && height >= minHeight;
        String rationale = "image is %dx%d; required minimum is %dx%d".formatted(width, height, minWidth, minHeight);
        return result(pass, rationale, evidence.id(), Map.of("width", width, "height", height));
    }

    private CriterionResult format(Criterion criterion, EvidenceEntry evidence) {
        String actual = String.valueOf(evidence.metadata().get("format")).toUpperCase(Locale.ROOT);
        Object allowedValue = criterion.verifier().params().get("allowed");
        List<?> allowed = allowedValue instanceof List<?> list ? list : List.of();
        boolean pass = allowed.stream().map(String::valueOf).map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(actual::equals);
        return result(pass, "image format is " + actual, evidence.id(), Map.of("format", actual));
    }

    private static int number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("missing numeric parameter " + key);
        return number.intValue();
    }

    private static CriterionResult result(boolean pass, String rationale, String evidenceId,
                                          Map<String, Object> diagnostics) {
        return new CriterionResult(pass ? CriterionVerdict.PASS : CriterionVerdict.FAIL,
                pass ? VerdictReason.RULE_MATCH : VerdictReason.RULE_MISMATCH,
                rationale, List.of(evidenceId), diagnostics);
    }

    private static CriterionResult inconclusive(VerdictReason reason, String rationale) {
        return new CriterionResult(CriterionVerdict.INCONCLUSIVE, reason,
                rationale, List.of(), Map.of());
    }
}
