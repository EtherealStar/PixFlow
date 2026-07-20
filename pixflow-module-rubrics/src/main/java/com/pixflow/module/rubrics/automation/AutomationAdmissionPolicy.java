package com.pixflow.module.rubrics.automation;

import com.pixflow.module.rubrics.judge.RepeatedLlmCriterionVerifier;
import com.pixflow.module.rubrics.run.ValidationReportRepository;
import com.pixflow.module.rubrics.template.LoadedTemplate;
import com.pixflow.module.rubrics.template.TemplateLifecycle;
import com.pixflow.module.rubrics.template.VerifierType;

/** 自动化资格同时受模板声明和精确 evaluator 校准事实约束。 */
public final class AutomationAdmissionPolicy {
    private final ValidationReportRepository reports;

    private final RepeatedLlmCriterionVerifier llm;

    public AutomationAdmissionPolicy(
            ValidationReportRepository reports, RepeatedLlmCriterionVerifier llm) {
        this.reports = reports;
        this.llm = llm;
    }

    public boolean allows(boolean enabled, LoadedTemplate loaded) {
        if (!enabled || loaded.template().lifecycle() == TemplateLifecycle.EXPERIMENTAL) {
            return false;
        }
        String evaluatorVersion = loaded.template().criteria().stream()
                .anyMatch(criterion -> criterion.verifier().type() == VerifierType.LLM)
                ? llm.expectedEvaluatorVersion(loaded.template().evaluator())
                : "deterministic:" + loaded.canonicalHash();
        return reports.hasPassingReportForRelease(
                loaded.template().id(), loaded.template().version(),
                loaded.canonicalHash(), evaluatorVersion);
    }
}
