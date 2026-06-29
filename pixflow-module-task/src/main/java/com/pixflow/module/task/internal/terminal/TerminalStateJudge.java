package com.pixflow.module.task.internal.terminal;

import com.pixflow.module.task.api.event.TaskCompletedEvent;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.internal.cancel.CancellationService;
import com.pixflow.module.task.internal.publish.TaskEventPublisher;
import java.time.Clock;

public class TerminalStateJudge {
    private final ProcessTaskMapper taskMapper;
    private final ProcessResultMapper resultMapper;
    private final CancellationService cancellationService;
    private final TaskEventPublisher eventPublisher;
    private final TaskMetrics metrics;
    private final Clock clock;

    public TerminalStateJudge(ProcessTaskMapper taskMapper,
                              ProcessResultMapper resultMapper,
                              CancellationService cancellationService,
                              TaskEventPublisher eventPublisher,
                              TaskMetrics metrics,
                              Clock clock) {
        this.taskMapper = taskMapper;
        this.resultMapper = resultMapper;
        this.cancellationService = cancellationService;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    public TaskStatus judge(long taskId) {
        ProcessTask task = taskMapper.selectById(taskId);
        int total = task.getTotalCount() == null ? resultMapper.findByTaskId(taskId).size() : task.getTotalCount();
        int success = resultMapper.countByStatus(taskId, ResultStatus.SUCCESS);
        int failed = resultMapper.countByStatus(taskId, ResultStatus.FAILED);
        int skipped = resultMapper.countByStatus(taskId, ResultStatus.SKIPPED);
        TaskStatus terminal;
        if (success == 0 && failed == 0 && skipped > 0) {
            terminal = TaskStatus.CANCELLED;
        } else if (success == 0 && failed > 0) {
            terminal = TaskStatus.FAILED;
        } else if (skipped > 0 || cancellationService.isCancelRequested(Long.toString(taskId))) {
            terminal = TaskStatus.PARTIAL;
        } else if (failed > 0 && success > 0) {
            terminal = TaskStatus.COMPLETED;
        } else {
            terminal = TaskStatus.COMPLETED;
        }
        taskMapper.markTerminal(taskId, terminal, total, success + failed + skipped,
                terminal == TaskStatus.FAILED ? "all units failed" : null, clock.instant());
        metrics.recordTerminal(terminal);
        eventPublisher.publishCompleted(new TaskCompletedEvent(Long.toString(taskId), task.getTaskType(),
                terminal, total, success, failed, clock.instant()));
        return terminal;
    }
}
