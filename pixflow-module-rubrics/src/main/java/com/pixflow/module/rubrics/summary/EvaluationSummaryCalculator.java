package com.pixflow.module.rubrics.summary;

import com.pixflow.module.rubrics.model.CriterionKind;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.model.QualityGate;
import java.util.List;

public final class EvaluationSummaryCalculator {
    public EvaluationSummary calculate(List<CriterionOutcome> outcomes) {
        List<CriterionOutcome> values = List.copyOf(outcomes);
        int pass = count(values, CriterionVerdict.PASS);
        int fail = count(values, CriterionVerdict.FAIL);
        int inconclusive = count(values, CriterionVerdict.INCONCLUSIVE);
        int notApplicable = count(values, CriterionVerdict.NOT_APPLICABLE);
        List<CriterionOutcome> hardRules = values.stream()
                .filter(value -> value.kind() == CriterionKind.HARD_RULE)
                .filter(value -> value.verdict() != CriterionVerdict.NOT_APPLICABLE)
                .toList();
        QualityGate gate = hardRules.stream().anyMatch(value -> value.verdict() == CriterionVerdict.FAIL)
                ? QualityGate.FAILED
                : hardRules.stream().anyMatch(value -> value.verdict() == CriterionVerdict.INCONCLUSIVE)
                        ? QualityGate.UNKNOWN : QualityGate.PASSED;
        List<CriterionOutcome> principles = values.stream()
                .filter(value -> value.kind() == CriterionKind.PRINCIPLE)
                .toList();
        int principlePass = count(principles, CriterionVerdict.PASS);
        int principleFail = count(principles, CriterionVerdict.FAIL);
        Double passRate = principlePass + principleFail == 0
                ? null : (double) principlePass / (principlePass + principleFail);
        int applicable = pass + fail + inconclusive;
        Double coverage = applicable == 0 ? null : (double) (pass + fail) / applicable;
        return new EvaluationSummary(gate, passRate, coverage, hardRules.size(), pass, fail,
                inconclusive, notApplicable);
    }

    private static int count(List<CriterionOutcome> values, CriterionVerdict verdict) {
        return (int) values.stream().filter(value -> value.verdict() == verdict).count();
    }
}
