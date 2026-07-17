package com.pixflow.module.task.internal.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.TaskType;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.internal.publish.TaskEventPublisher;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class TerminalStateJudgeTest {
    @Test
    void appliesCanonicalTerminalAggregation() {
        assertThat(TerminalStateJudge.decide(2, 2, 0, 0)).isEqualTo(TaskStatus.COMPLETED);
        assertThat(TerminalStateJudge.decide(2, 1, 1, 0)).isEqualTo(TaskStatus.PARTIAL);
        assertThat(TerminalStateJudge.decide(2, 0, 2, 0)).isEqualTo(TaskStatus.FAILED);
        assertThat(TerminalStateJudge.decide(2, 0, 0, 2)).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void doesNotPublishCompletionWhenEpochFencedUpdateLosesOwnership() {
        ProcessTaskMapper tasks = mock(ProcessTaskMapper.class);
        ProcessResultMapper results = mock(ProcessResultMapper.class);
        TaskEventPublisher events = mock(TaskEventPublisher.class);
        ProcessTask running = new ProcessTask();
        running.setId(9L);
        running.setTaskType(TaskType.IMAGE_PROCESS);
        running.setStatus(TaskStatus.RUNNING);
        running.setTotalCount(2);
        ProcessTask newerOwner = new ProcessTask();
        newerOwner.setStatus(TaskStatus.RUNNING);
        when(tasks.selectById(9L)).thenReturn(running, newerOwner);
        when(results.countByStatus(9L, ResultStatus.SUCCESS)).thenReturn(2);
        when(results.countByStatus(9L, ResultStatus.FAILED)).thenReturn(0);
        when(results.countByStatus(9L, ResultStatus.SKIPPED)).thenReturn(0);
        when(tasks.markTerminalEpoch(eq(9L), eq(3L), eq(TaskStatus.COMPLETED),
                eq(2), eq(2), eq(null), any())).thenReturn(0);

        TerminalStateJudge judge = new TerminalStateJudge(tasks, results, events,
                mock(TaskMetrics.class), Clock.systemUTC());

        assertThat(judge.judge(9L, 3L)).isEqualTo(TaskStatus.RUNNING);
        verify(events, never()).publishCompleted(any());
    }
}
