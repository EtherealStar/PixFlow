package com.pixflow.module.rubrics.automation;

import com.pixflow.module.rubrics.api.DatasetSelection;
import com.pixflow.module.rubrics.api.EvaluationRunId;
import com.pixflow.module.rubrics.api.EvaluationRunRequest;
import com.pixflow.module.rubrics.api.RubricsEvaluationService;
import com.pixflow.module.rubrics.api.RunPurpose;
import com.pixflow.module.rubrics.api.RunTrigger;
import com.pixflow.module.rubrics.api.TemplateRef;
import com.pixflow.module.rubrics.config.RubricsProperties;
import com.pixflow.module.rubrics.run.RunAdmissionService;
import com.pixflow.module.rubrics.observability.RubricsMetrics;
import com.pixflow.module.rubrics.template.TemplateRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 只运行显式绑定模板版本与 Dataset 版本的离线日批。 */
public final class RubricsScheduledDatasetRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(RubricsScheduledDatasetRunner.class);

    private final RubricsEvaluationService evaluations;

    private final RunAdmissionService admissions;

    private final TemplateRegistry templates;

    private final RubricsProperties properties;

    private final AutomationAdmissionPolicy policy;

    private final Executor executor;

    private final Clock clock;

    private final RubricsMetrics metrics;

    public RubricsScheduledDatasetRunner(
            RubricsEvaluationService evaluations,
            RunAdmissionService admissions,
            TemplateRegistry templates,
            RubricsProperties properties,
            AutomationAdmissionPolicy policy,
            Executor executor,
            Clock clock,
            RubricsMetrics metrics) {
        this.evaluations = evaluations;
        this.admissions = admissions;
        this.templates = templates;
        this.properties = properties;
        this.policy = policy;
        this.executor = executor;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${pixflow.rubrics.automation.scheduled.interval:PT24H}")
    public void run() {
        RubricsProperties.ScheduledBinding config = properties.getAutomation().getScheduled();
        if (!complete(config)) {
            metrics.recordAutomationSkipped("SCHEDULED", "INCOMPLETE_BINDING");
            return;
        }
        var loaded = templates.require(config.getTemplateId(), config.getTemplateVersion());
        if (!policy.allows(config.isEnabled(), loaded)) {
            metrics.recordAutomationSkipped("SCHEDULED", "NOT_ELIGIBLE");
            return;
        }
        EvaluationRunRequest request = new EvaluationRunRequest(
                new TemplateRef(config.getTemplateId(), config.getTemplateVersion()),
                RunPurpose.PRODUCTION_SAMPLE,
                RunTrigger.SCHEDULED,
                new DatasetSelection(config.getDatasetId(), config.getDatasetVersion()),
                null);
        String admissionKey = String.join(":",
                "scheduled",
                LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString(),
                config.getTemplateId(),
                config.getTemplateVersion(),
                config.getDatasetId(),
                config.getDatasetVersion());
        EvaluationRunId runId = admissions.admit(request, admissionKey);
        try {
            executor.execute(() -> resume(runId));
        } catch (RejectedExecutionException error) {
            LOGGER.debug("Rubrics scheduled queue is full; run {} remains pending", runId.value());
            metrics.recordAutomationSkipped("SCHEDULED", "QUEUE_FULL");
        }
    }

    private void resume(EvaluationRunId runId) {
        try {
            evaluations.resume(runId);
        } catch (RuntimeException error) {
            LOGGER.warn("Rubrics scheduled evaluation was isolated for run {}: {}",
                    runId.value(), error.getClass().getSimpleName());
        }
    }

    private static boolean complete(RubricsProperties.ScheduledBinding config) {
        return config.isEnabled()
                && !config.getTemplateId().isBlank()
                && !config.getTemplateVersion().isBlank()
                && !config.getDatasetId().isBlank()
                && !config.getDatasetVersion().isBlank();
    }
}
