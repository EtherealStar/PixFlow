package com.pixflow.module.rubrics.judge;

import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.verifier.VerdictReason;
import java.util.List;

public final class MajorityVerdictReducer {
    public MajorityVerdict reduce(List<JudgeRollout> rollouts, int configuredRollouts) {
        if (configuredRollouts < 1 || rollouts.size() != configuredRollouts) {
            throw new IllegalArgumentException("rollout count must equal configured rollouts");
        }
        long pass = count(rollouts, CriterionVerdict.PASS);
        long fail = count(rollouts, CriterionVerdict.FAIL);
        int majority = configuredRollouts / 2 + 1;
        if (pass >= majority) return new MajorityVerdict(CriterionVerdict.PASS, VerdictReason.RULE_MATCH,
                (double) pass / configuredRollouts);
        if (fail >= majority) return new MajorityVerdict(CriterionVerdict.FAIL, VerdictReason.RULE_MISMATCH,
                (double) fail / configuredRollouts);
        return new MajorityVerdict(CriterionVerdict.INCONCLUSIVE, VerdictReason.JUDGE_DISAGREEMENT,
                (double) Math.max(pass, fail) / configuredRollouts);
    }

    private static long count(List<JudgeRollout> rollouts, CriterionVerdict verdict) {
        return rollouts.stream().filter(rollout -> rollout.verdict() == verdict).count();
    }
}
