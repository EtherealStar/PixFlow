package com.pixflow.module.task.internal.worker;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.WorkUnit;
import com.pixflow.module.task.infra.lock.TaskLockManager;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.mq.TaskMessage;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.internal.scheduler.WorkUnitScheduler;
import com.pixflow.module.task.internal.terminal.TerminalStateJudge;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class TaskWorker {
    private final ProcessTaskMapper taskMapper;
    private final ProcessResultMapper resultMapper;
    private final WorkerRouter router;
    private final WorkUnitScheduler scheduler;
    private final TerminalStateJudge terminalStateJudge;
    private final TaskLockManager lockManager;
    private final TaskMetrics metrics;
    private final TaskProperties properties;
    private final Clock clock;

    public TaskWorker(ProcessTaskMapper taskMapper,
                      ProcessResultMapper resultMapper,
                      WorkerRouter router,
                      WorkUnitScheduler scheduler,
                      TerminalStateJudge terminalStateJudge,
                      TaskLockManager lockManager,
                      TaskMetrics metrics,
                      TaskProperties properties,
                      Clock clock) {
        this.taskMapper = taskMapper;
        this.resultMapper = resultMapper;
        this.router = router;
        this.scheduler = scheduler;
        this.terminalStateJudge = terminalStateJudge;
        this.lockManager = lockManager;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    public void handle(TaskMessage message) {
        boolean accepted = lockManager.tryRunWithTaskLock(message.taskId(), () -> runLocked(message));
        if (!accepted) {
            metrics.recordLockContention();
        }
    }

    private void runLocked(TaskMessage message) {
        long taskId = Long.parseLong(message.taskId());
        ProcessTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new PixFlowException(TaskErrorCode.TASK_NOT_FOUND, "task not found: " + taskId);
        }
        if (task.getStatus().terminal()) {
            return;
        }
        TaskStatus from = task.getStatus() == TaskStatus.PENDING ? TaskStatus.PENDING : TaskStatus.QUEUED;
        if (task.getStatus() == TaskStatus.PENDING) {
            taskMapper.transit(taskId, TaskStatus.PENDING, TaskStatus.QUEUED, clock.instant());
        }
        taskMapper.markRunning(taskId, TaskStatus.QUEUED, TaskStatus.RUNNING, workerId(), clock.instant());
        task = taskMapper.selectById(taskId);
        List<WorkUnit> units = router.plan(task);
        task.setTotalCount(units.size());
        taskMapper.updateById(task);
        UnitExecutionContext context = new UnitExecutionContext(UUID.randomUUID().toString(),
                task.getAttemptCount() == null ? 1 : task.getAttemptCount(), units.size());
        List<CompletableFuture<Void>> futures = scheduler.submitAll(units, unit -> {
            if (resultMapper.findByUnit(taskId, unit.memberId(), unit.branchId()) != null) {
                return;
            }
            router.execute(unit, context);
        });
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        try {
            all.get(properties.getTerminal().getJudgeTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new PixFlowException(TaskErrorCode.TASK_WORKER_REJECTED, "task worker timed out", e);
        }
        int terminalResults = resultMapper.countByStatus(taskId, ResultStatus.SUCCESS)
                + resultMapper.countByStatus(taskId, ResultStatus.FAILED)
                + resultMapper.countByStatus(taskId, ResultStatus.SKIPPED);
        if (terminalResults < units.size()) {
            throw new PixFlowException(TaskErrorCode.TASK_RESULT_WRITE_FAILED, "not all units reached terminal state");
        }
        terminalStateJudge.judge(taskId);
    }

    private static String workerId() {
        return java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
    }
}
