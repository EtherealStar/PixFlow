package com.pixflow.module.task.internal.terminal;

import com.pixflow.module.task.api.event.TaskCompletedEvent;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.internal.publish.TaskEventPublisher;
import java.time.Clock;

public class TerminalStateJudge {
    private final ProcessTaskMapper taskMapper;
    private final ProcessResultMapper resultMapper;
    private final TaskEventPublisher eventPublisher;
    private final TaskMetrics metrics;
    private final Clock clock;

    public TerminalStateJudge(ProcessTaskMapper taskMapper,
                              ProcessResultMapper resultMapper,
                              TaskEventPublisher eventPublisher,
                              TaskMetrics metrics,
                              Clock clock) {
        this.taskMapper = taskMapper;
        this.resultMapper = resultMapper;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    public TaskStatus judge(long taskId, long runEpoch) {
        ProcessTask task = taskMapper.selectById(taskId);
        int total = task.getTotalCount() == null ? resultMapper.findByTaskId(taskId).size() : task.getTotalCount();
        int success = resultMapper.countByStatus(taskId, ResultStatus.SUCCESS);
        int failed = resultMapper.countByStatus(taskId, ResultStatus.FAILED);
        int skipped = resultMapper.countByStatus(taskId, ResultStatus.SKIPPED);
        TaskStatus terminal = decide(total, success, failed, skipped);
        int updated = taskMapper.markTerminalEpoch(taskId, runEpoch, terminal, total,
                success + failed + skipped, terminal == TaskStatus.FAILED ? "all units failed" : null,
                clock.instant());
        if (updated != 1) return taskMapper.selectById(taskId).getStatus();
        metrics.recordTerminal(terminal);
        eventPublisher.publishCompleted(new TaskCompletedEvent(Long.toString(taskId), task.getTaskType(),
                terminal, total, success, failed, clock.instant()));
        return terminal;
    }

    static TaskStatus decide(int total, int success, int failed, int skipped) {
        if (total > 0 && success == total) return TaskStatus.COMPLETED;
        if (success == 0 && failed > 0) return TaskStatus.FAILED;
        if (success > 0 && failed + skipped > 0) return TaskStatus.PARTIAL;
        if (total > 0 && success == 0 && skipped == total) return TaskStatus.CANCELLED;
        throw new IllegalStateException("结果计数尚未收敛到终态");
    }
}
