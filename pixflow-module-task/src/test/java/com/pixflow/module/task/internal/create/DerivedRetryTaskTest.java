package com.pixflow.module.task.internal.create;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.harness.state.model.UnitKeyCodec;
import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.module.task.api.command.RetryFailedTaskCommand;
import com.pixflow.module.task.api.command.TaskId;
import com.pixflow.module.task.domain.idempotency.IdempotencyGuard;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.TaskType;
import com.pixflow.module.task.infra.cache.TaskIdempotencyStore;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.mq.TaskMessagePublisher;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.internal.cancel.CancellationService;
import com.pixflow.module.task.internal.planning.WorkUnitPlanner;
import com.pixflow.module.task.internal.planning.WorkUnitSelection;
import com.pixflow.module.task.internal.publish.TaskEventPublisher;
import com.pixflow.module.task.internal.retry.RetryFailedTaskService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DerivedRetryTaskTest {
    @Test
    void retryFailedCreatesQueuedTaskFromOnlyFailedFrozenUnits() throws Exception {
        ProcessTaskMapper tasks = mock(ProcessTaskMapper.class);
        ProcessResultMapper results = mock(ProcessResultMapper.class);
        TaskIdempotencyStore idempotencyStore = mock(TaskIdempotencyStore.class);
        when(idempotencyStore.get(any())).thenReturn(Optional.empty());
        when(tasks.findByIdempotencyKey(any())).thenReturn(null);

        ProcessTask source = sourceTask();
        when(tasks.lockById(10L)).thenReturn(source);
        var failed = new ProcessResult();
        failed.setUnitKey(UnitKeyCodec.encode(new UnitKey("10", UnitKind.BRANCH, "image-1", "branch-a")));
        when(results.findByTaskIdAndStatus(10L, ResultStatus.FAILED)).thenReturn(List.of(failed));
        when(tasks.transit(101L, TaskStatus.PENDING, TaskStatus.QUEUED, Instant.parse("2026-07-13T03:00:00Z")))
                .thenReturn(1);
        doAnswer(invocation -> {
            ProcessTask inserted = invocation.getArgument(0);
            inserted.setId(101L);
            return 1;
        }).when(tasks).insert(any(ProcessTask.class));

        PendingTaskEnqueuer enqueuer = mock(PendingTaskEnqueuer.class);
        RetryFailedTaskService service = new RetryFailedTaskService(tasks, results,
                new IdempotencyGuard(idempotencyStore, tasks), enqueuer, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-13T03:00:00Z"), ZoneOffset.UTC),
                mock(TaskEventPublisher.class));

        var response = service.retry(new RetryFailedTaskCommand(new TaskId("10"), "request-1"));

        assertThat(response.taskId()).isEqualTo("101");
        assertThat(response.retryOfTaskId()).isEqualTo("10");
        assertThat(response.selectedUnitCount()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(TaskStatus.QUEUED);
        verify(enqueuer).enqueue(101L);

        ArgumentCaptor<ProcessTask> task = ArgumentCaptor.forClass(ProcessTask.class);
        verify(tasks).insert(task.capture());
        WorkUnitSelection selection = new ObjectMapper().readValue(
                task.getValue().getUnitSelectionJson(), WorkUnitSelection.class);
        assertThat(selection.items()).extracting(WorkUnitSelection.Item::memberId)
                .containsExactly("image-1");
        assertThat(task.getValue().getRetryOfTaskId()).isEqualTo(10L);

        ArgumentCaptor<ProcessResult> pending = ArgumentCaptor.forClass(ProcessResult.class);
        verify(results).insert(pending.capture());
        assertThat(pending.getValue().getTaskId()).isEqualTo(101L);
        assertThat(pending.getValue().getStatus()).isEqualTo(ResultStatus.PENDING);
    }

    private static ProcessTask sourceTask() throws Exception {
        ProcessTask source = new ProcessTask();
        source.setId(10L);
        source.setTaskType(TaskType.IMAGE_PROCESS);
        source.setConversationId("conversation-1");
        source.setPackageId(7L);
        source.setPriority(0);
        source.setStatus(TaskStatus.PARTIAL);
        source.setDagJson("{}");
        source.setPayloadHash("hash");
        source.setSchemaVersion("1.0");
        var failed = new WorkUnitSelection.Item(UnitKind.BRANCH, "image-1", "branch-a", List.of());
        var succeeded = new WorkUnitSelection.Item(UnitKind.BRANCH, "image-2", "branch-a", List.of());
        source.setUnitSelectionJson(new ObjectMapper().writeValueAsString(
                new WorkUnitSelection(List.of(failed, succeeded))));
        return source;
    }
}
