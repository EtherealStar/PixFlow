package com.pixflow.module.rubrics.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.module.rubrics.api.DatasetSelection;
import com.pixflow.module.rubrics.api.EvaluationRunId;
import com.pixflow.module.rubrics.api.EvaluationRunRequest;
import com.pixflow.module.rubrics.api.RubricsEvaluationService;
import com.pixflow.module.rubrics.config.RubricsProperties;
import com.pixflow.module.rubrics.persistence.RubricsRunEntity;
import com.pixflow.module.rubrics.observability.RubricsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.pixflow.module.rubrics.persistence.RubricsRunMapper;
import com.pixflow.module.rubrics.run.RunAdmissionService;
import com.pixflow.module.rubrics.template.LoadedTemplate;
import com.pixflow.module.rubrics.template.RubricTemplate;
import com.pixflow.module.rubrics.template.TemplateLifecycle;
import com.pixflow.module.rubrics.template.TemplateRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OfflineAutomationTest {
    @Test
    void recoverySubmitsPersistedRunIdentity() {
        RubricsEvaluationService evaluations = mock(RubricsEvaluationService.class);
        RubricsRunMapper runs = mock(RubricsRunMapper.class);
        RubricsRunEntity pending = new RubricsRunEntity();
        pending.setId(17L);
        when(runs.findRecoverable(25)).thenReturn(List.of(pending));
        RubricsProperties properties = new RubricsProperties();
        properties.setRecoveryBatchSize(25);
        var recovery = new RubricsRecoveryScheduler(
                evaluations, runs, properties, Runnable::run,
                new RubricsMetrics(new SimpleMeterRegistry()));

        recovery.recover();

        verify(evaluations).resume(new EvaluationRunId(17));
    }

    @Test
    void scheduledBindingPinsTemplateAndDatasetVersions() {
        RubricsEvaluationService evaluations = mock(RubricsEvaluationService.class);
        RunAdmissionService admissions = mock(RunAdmissionService.class);
        TemplateRegistry templates = mock(TemplateRegistry.class);
        RubricTemplate template = mock(RubricTemplate.class);
        when(template.lifecycle()).thenReturn(TemplateLifecycle.PRODUCTION);
        when(templates.require("image-quality", "2.0.0"))
                .thenReturn(new LoadedTemplate(template, "hash", "test"));
        when(admissions.admit(any(), anyString())).thenReturn(new EvaluationRunId(23));
        RubricsProperties properties = new RubricsProperties();
        var binding = properties.getAutomation().getScheduled();
        binding.setEnabled(true);
        binding.setTemplateId("image-quality");
        binding.setTemplateVersion("2.0.0");
        binding.setDatasetId("image-holdout");
        binding.setDatasetVersion("1.3.0");
        AutomationAdmissionPolicy policy = mock(AutomationAdmissionPolicy.class);
        when(policy.allows(anyBoolean(), any(LoadedTemplate.class))).thenReturn(true);
        var runner = new RubricsScheduledDatasetRunner(
                evaluations,
                admissions,
                templates,
                properties,
                policy,
                Runnable::run,
                Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC),
                new RubricsMetrics(new SimpleMeterRegistry()));

        runner.run();

        ArgumentCaptor<EvaluationRunRequest> request =
                ArgumentCaptor.forClass(EvaluationRunRequest.class);
        verify(admissions).admit(request.capture(), anyString());
        assertThat(request.getValue().selection())
                .isEqualTo(new DatasetSelection("image-holdout", "1.3.0"));
        verify(evaluations).resume(new EvaluationRunId(23));
    }
}
