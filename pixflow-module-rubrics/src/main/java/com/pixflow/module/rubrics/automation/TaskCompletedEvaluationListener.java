package com.pixflow.module.rubrics.automation;

import com.pixflow.module.rubrics.api.EvaluationRunRequest;
import com.pixflow.module.rubrics.api.EvaluationRunId;
import com.pixflow.module.rubrics.api.ExplicitSubjects;
import com.pixflow.module.rubrics.api.RubricsEvaluationService;
import com.pixflow.module.rubrics.api.RunPurpose;
import com.pixflow.module.rubrics.api.RunTrigger;
import com.pixflow.module.rubrics.api.TemplateRef;
import com.pixflow.module.rubrics.config.RubricsProperties;
import com.pixflow.module.rubrics.model.SubjectType;
import com.pixflow.module.rubrics.observability.RubricsMetrics;
import com.pixflow.module.rubrics.run.RunAdmissionService;
import com.pixflow.module.rubrics.template.TemplateRegistry;
import com.pixflow.module.task.api.TaskOutcomeQuery;
import com.pixflow.module.task.api.event.TaskCompletedEvent;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

public final class TaskCompletedEvaluationListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskCompletedEvaluationListener.class);

    private final RubricsEvaluationService service;

    private final RunAdmissionService admissions;

    private final TemplateRegistry templates;

    private final TaskOutcomeQuery outcomes;

    private final RubricsProperties properties;

    private final AutomationAdmissionPolicy policy;

    private final Executor executor;

    private final RubricsMetrics metrics;

    public TaskCompletedEvaluationListener(
            RubricsEvaluationService service,
            RunAdmissionService admissions,
            TemplateRegistry templates,
            TaskOutcomeQuery outcomes,
            RubricsProperties properties,
            AutomationAdmissionPolicy policy,
            Executor executor,
            RubricsMetrics metrics) {
        this.service = service;
        this.admissions = admissions;
        this.templates = templates;
        this.outcomes = outcomes;
        this.properties = properties;
        this.policy = policy;
        this.executor = executor;
        this.metrics = metrics;
    }

    @EventListener
    public void onCompleted(TaskCompletedEvent event) {
        try {
            EvaluationRunId runId = admit(event);
            if (runId == null) {
                return;
            }
            executor.execute(() -> resume(runId, event.taskId()));
        } catch (RejectedExecutionException error) {
            // admission 已经持久化；队列满时由 recovery scan 或显式 resume 接管。
            LOGGER.warn("Rubrics event evaluation queue is full for task {}; run remains pending",
                    event.taskId());
            metrics.recordAutomationSkipped("TASK_COMPLETED", "QUEUE_FULL");
        } catch (RuntimeException error) {
            LOGGER.warn("Rubrics event admission was isolated for task {}: {}",
                    event.taskId(), error.getClass().getSimpleName());
            metrics.recordAutomationSkipped("TASK_COMPLETED", "ADMISSION_ERROR");
        }
    }

    private EvaluationRunId admit(TaskCompletedEvent event) {
        RubricsProperties.EventBinding config = properties.getAutomation().getEvent();
        if (config.getTemplateId().isBlank() || config.getTemplateVersion().isBlank()) {
            metrics.recordAutomationSkipped("TASK_COMPLETED", "INCOMPLETE_BINDING");
            return null;
        }
        var loaded = templates.require(config.getTemplateId(), config.getTemplateVersion());
        if (!policy.allows(config.isEnabled(), loaded)) {
            metrics.recordAutomationSkipped("TASK_COMPLETED", "NOT_ELIGIBLE");
            return null;
        }
        if (!sampled(event.taskId(), config.getTemplateId(), config.getSamplePermille())) {
            metrics.recordAutomationSkipped("TASK_COMPLETED", "NOT_SAMPLED");
            return null;
        }
        var subjectIds = outcomes.successfulResults(Long.parseLong(event.taskId())).stream()
                .map(snapshot -> Long.toString(snapshot.resultId()))
                .toList();
        if (subjectIds.isEmpty()) {
            metrics.recordAutomationSkipped("TASK_COMPLETED", "NO_SUBJECT");
            return null;
        }
        EvaluationRunRequest request = new EvaluationRunRequest(
                new TemplateRef(config.getTemplateId(), config.getTemplateVersion()),
                RunPurpose.PRODUCTION_SAMPLE,
                RunTrigger.EVENT_DRIVEN,
                new ExplicitSubjects(SubjectType.IMAGE_RESULT, subjectIds),
                null);
        String admissionKey = String.join(":",
                "task-completed",
                event.taskId(),
                config.getTemplateId(),
                config.getTemplateVersion());
        return admissions.admit(request, admissionKey);
    }

    private static boolean sampled(
            String taskId, String templateId, int samplePermille) {
        if (samplePermille < 0 || samplePermille > 1000) {
            throw new IllegalArgumentException("event sample permille must be between 0 and 1000");
        }
        int bucket = Math.floorMod((taskId + ":" + templateId).hashCode(), 1000);
        return bucket < samplePermille;
    }

    private void resume(EvaluationRunId runId, String taskId) {
        try {
            service.resume(runId);
        } catch (RuntimeException error) {
            LOGGER.warn("Rubrics event evaluation was isolated for task {}: {}",
                    taskId, error.getClass().getSimpleName());
        }
    }
}
