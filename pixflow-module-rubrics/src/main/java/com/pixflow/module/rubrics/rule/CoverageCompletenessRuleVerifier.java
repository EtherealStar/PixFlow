package com.pixflow.module.rubrics.rule;

import com.pixflow.module.rubrics.model.EvidenceRef;
import com.pixflow.module.rubrics.model.EvidenceType;
import java.util.List;

public class CoverageCompletenessRuleVerifier implements RuleVerifier {
    @Override
    public String dimensionKey() {
        return "coverage_completeness";
    }

    @Override
    public RuleCheckResult verify(RuleCheckInput input) {
        int total = intContext(input, "task_total_count", 0);
        int done = intContext(input, "task_done_count", 0);
        String detail = "done=" + done + ", total=" + total;
        List<EvidenceRef> evidence = List.of(new EvidenceRef(EvidenceType.DATA, taskRef(input), detail, null));
        if (total <= 0 || done >= total) {
            return RuleCheckResult.pass(detail, evidence);
        }
        return RuleCheckResult.fail(detail, evidence);
    }

    private static int intContext(RuleCheckInput input, String key, int defaultValue) {
        Object value = input.taskContext().get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static String taskRef(RuleCheckInput input) {
        return input.result() == null || input.result().getTaskId() == null ? "" : String.valueOf(input.result().getTaskId());
    }
}
