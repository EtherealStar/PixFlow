package com.pixflow.module.rubrics.automation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.module.rubrics.api.RubricsEvaluationService;
import com.pixflow.module.rubrics.config.RubricsProperties;
import com.pixflow.module.rubrics.model.SubjectType;
import com.pixflow.module.rubrics.template.LoadedTemplate;
import com.pixflow.module.rubrics.template.RubricTemplate;
import com.pixflow.module.rubrics.template.TemplateLifecycle;
import com.pixflow.module.rubrics.template.TemplateRegistry;
import com.pixflow.module.task.api.TaskOutcomeQuery;
import com.pixflow.module.task.api.event.TaskCompletedEvent;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.TaskType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskCompletedEvaluationListenerTest {
    @Test
    void persistsTaskCompletedAsTheRunTrigger() {
        RubricsEvaluationService service = mock(RubricsEvaluationService.class);
        TemplateRegistry templates = mock(TemplateRegistry.class);
        TaskOutcomeQuery outcomes = mock(TaskOutcomeQuery.class);
        RubricsProperties properties = new RubricsProperties();
        properties.getAutomation().setEventEnabled(true);
        properties.getAutomation().setTemplateId("image-result-quality");
        properties.getAutomation().setTemplateVersion("2.0.0");
        RubricTemplate template = mock(RubricTemplate.class);
        when(template.lifecycle()).thenReturn(TemplateLifecycle.PRODUCTION);
        when(templates.require("image-result-quality", "2.0.0"))
                .thenReturn(new LoadedTemplate(template, "hash", "test"));
        when(outcomes.successfulResults(42)).thenReturn(List.of(new TaskOutcomeQuery.SuccessfulResultSnapshot(
                7, 42, "STANDARD", "image", "sku", null, null, "branch",
                31, "package:5/image:31", 10, Instant.EPOCH)));
        var listener = new TaskCompletedEvaluationListener(service, templates, outcomes, properties,
                new AutomationAdmissionPolicy(), Runnable::run);

        listener.onCompleted(new TaskCompletedEvent("42", TaskType.IMAGE_PROCESS, TaskStatus.COMPLETED,
                1, 1, 0, Instant.EPOCH));

        verify(service).start(any());
    }
}
