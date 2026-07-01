package com.pixflow.module.rubrics.rule;

import com.pixflow.module.rubrics.model.Confidence;
import com.pixflow.module.rubrics.model.EvidenceRef;
import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.model.Verdict;
import java.util.List;
import java.util.OptionalDouble;

public class HitlSmoothnessRuleVerifier implements RuleVerifier {
    @Override
    public String dimensionKey() {
        return "hitl_smoothness";
    }

    @Override
    public RuleCheckResult verify(RuleCheckInput input) {
        int confirmations = intContext(input, "confirm_count", 0);
        String detail = "confirm_count=" + confirmations;
        List<EvidenceRef> evidence = List.of(new EvidenceRef(EvidenceType.DATA, taskRef(input), detail, null));
        if (confirmations == 0) {
            return new RuleCheckResult(Verdict.PASS, Confidence.LOW, detail, evidence, OptionalDouble.of(60));
        }
        if (confirmations <= 2) {
            return RuleCheckResult.pass(detail, evidence);
        }
        return RuleCheckResult.fail(detail, evidence);
    }

    private static int intContext(RuleCheckInput input, String key, int defaultValue) {
        Object value = input.taskContext().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }

    private static String taskRef(RuleCheckInput input) {
        return input.result() == null || input.result().getTaskId() == null ? "" : String.valueOf(input.result().getTaskId());
    }
}
