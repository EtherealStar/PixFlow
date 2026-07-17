package com.pixflow.module.rubrics.automation;

import com.pixflow.module.rubrics.api.RubricsEvaluationService;
import com.pixflow.module.rubrics.api.RunEvaluationCommand;
import com.pixflow.module.rubrics.config.RubricsProperties;
import com.pixflow.module.rubrics.model.SubjectType;
import com.pixflow.module.rubrics.run.RunTriggerType;
import com.pixflow.module.rubrics.template.TemplateRegistry;
import com.pixflow.module.task.api.TaskOutcomeQuery;
import com.pixflow.module.task.api.event.TaskCompletedEvent;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

public final class TaskCompletedEvaluationListener {
    private static final Logger log = LoggerFactory.getLogger(TaskCompletedEvaluationListener.class);
    private final RubricsEvaluationService service;
    private final TemplateRegistry templates;
    private final TaskOutcomeQuery outcomes;
    private final RubricsProperties properties;
    private final AutomationAdmissionPolicy policy;
    private final Executor executor;

    public TaskCompletedEvaluationListener(RubricsEvaluationService service, TemplateRegistry templates,
                                           TaskOutcomeQuery outcomes, RubricsProperties properties,
                                           AutomationAdmissionPolicy policy, Executor executor) {
        this.service = service;
        this.templates = templates;
        this.outcomes = outcomes;
        this.properties = properties;
        this.policy = policy;
        this.executor = executor;
    }

    @EventListener
    public void onCompleted(TaskCompletedEvent event) {
        try {
            executor.execute(() -> evaluate(event));
        } catch (RejectedExecutionException error) {
            log.warn("Rubrics event evaluation queue is full for task {}", event.taskId());
        }
    }

    private void evaluate(TaskCompletedEvent event) {
        try {
            RubricsProperties.Automation config = properties.getAutomation();
            if (config.getTemplateId().isBlank() || config.getTemplateVersion().isBlank()) return;
            var loaded = templates.require(config.getTemplateId(), config.getTemplateVersion());
            if (!policy.allows(config.isEventEnabled(), loaded.template().lifecycle())) return;
            var subjectIds = outcomes.successfulResults(Long.parseLong(event.taskId())).stream()
                    .map(snapshot -> Long.toString(snapshot.resultId()))
                    .toList();
            if (subjectIds.isEmpty()) return;
            service.start(new RunEvaluationCommand(config.getTemplateId(), config.getTemplateVersion(),
                    SubjectType.IMAGE_RESULT, null, null, subjectIds), RunTriggerType.TASK_COMPLETED);
        } catch (RuntimeException error) {
            log.warn("Rubrics event evaluation was isolated for task {}: {}",
                    event.taskId(), error.getClass().getSimpleName());
        }
    }
}
