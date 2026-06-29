package com.pixflow.module.task.internal.cancel;

import com.pixflow.module.task.api.command.CancelTaskCommand;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.infra.cache.TaskCancelFlag;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.time.Clock;

public class CancellationService {
    private final ProcessTaskMapper taskMapper;
    private final TaskCancelFlag cancelFlag;
    private final TaskMetrics metrics;
    private final Clock clock;

    public CancellationService(ProcessTaskMapper taskMapper, TaskCancelFlag cancelFlag,
                               TaskMetrics metrics, Clock clock) {
        this.taskMapper = taskMapper;
        this.cancelFlag = cancelFlag;
        this.metrics = metrics;
        this.clock = clock;
    }

    public boolean cancel(CancelTaskCommand command) {
        ProcessTask task = taskMapper.selectById(Long.parseLong(command.taskId().value()));
        if (task == null) {
            metrics.recordCancel("missing");
            return false;
        }
        if (task.getStatus().terminal()) {
            metrics.recordCancel("terminal");
            return false;
        }
        cancelFlag.requestCancel(command.taskId().value(), command.reason());
        if (task.getStatus() == TaskStatus.QUEUED) {
            taskMapper.transit(task.getId(), TaskStatus.QUEUED, TaskStatus.CANCELLED, clock.instant());
            metrics.recordCancel("queued");
            return true;
        }
        metrics.recordCancel("running");
        return true;
    }

    public boolean isCancelRequested(String taskId) {
        return cancelFlag.isCancelRequested(taskId);
    }
}
