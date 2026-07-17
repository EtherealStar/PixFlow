package com.pixflow.module.task.internal.create;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.TaskType;
import com.pixflow.module.task.infra.mq.TaskMessagePublisher;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PendingTaskEnqueuerTest {
    @Test
    void publishFailureReturnsQueuedClaimToPendingForRetry() {
        ProcessTaskMapper tasks = mock(ProcessTaskMapper.class);
        TaskMessagePublisher publisher = mock(TaskMessagePublisher.class);
        ProcessTask pending = new ProcessTask();
        pending.setId(12L);
        pending.setTaskType(TaskType.IMAGE_PROCESS);
        pending.setStatus(TaskStatus.PENDING);
        pending.setPriority(0);
        pending.setSchemaVersion("1.0");
        when(tasks.selectById(12L)).thenReturn(pending);
        Instant now = Instant.parse("2026-07-13T03:00:00Z");
        when(tasks.transit(12L, TaskStatus.PENDING, TaskStatus.QUEUED, now)).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("mq unavailable"))
                .when(publisher).publish(any());

        PendingTaskEnqueuer enqueuer = new PendingTaskEnqueuer(tasks, publisher,
                new TaskProperties(), Clock.fixed(now, ZoneOffset.UTC));

        assertThatThrownBy(() -> enqueuer.enqueue(12L)).isInstanceOf(IllegalStateException.class);
        verify(tasks).resetFailedEnqueue(12L, now);
    }
}
