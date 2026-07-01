package com.pixflow.module.rubrics.rule;

import com.pixflow.module.rubrics.model.EvidenceRef;
import com.pixflow.module.rubrics.model.EvidenceType;
import java.util.List;

public class ParamValidityRuleVerifier implements RuleVerifier {
    @Override
    public String dimensionKey() {
        return "param_validity";
    }

    @Override
    public RuleCheckResult verify(RuleCheckInput input) {
        String dagJson = String.valueOf(input.taskContext().getOrDefault("dag_json", ""));
        boolean hasContradictoryDuplicate = dagJson.contains("\"conflict\":true") || dagJson.contains("\"invalid\":true");
        String detail = hasContradictoryDuplicate
                ? "dag_json contains explicit invalid/conflict marker"
                : "no explicit invalid/conflict marker in dag_json";
        List<EvidenceRef> evidence = List.of(new EvidenceRef(EvidenceType.DATA, taskRef(input), detail, null));
        return hasContradictoryDuplicate ? RuleCheckResult.fail(detail, evidence) : RuleCheckResult.pass(detail, evidence);
    }

    private static String taskRef(RuleCheckInput input) {
        return input.result() == null || input.result().getTaskId() == null ? "" : String.valueOf(input.result().getTaskId());
    }
}
