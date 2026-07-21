package com.pixflow.module.task.internal.worker;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.state.recovery.RecoveryCoordinator;
import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.api.publication.GeneratedAssetPublicationPort;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.WorkUnit;
import com.pixflow.module.task.infra.lock.TaskLockManager;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.mq.TaskMessage;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessResultMemberMapper;
import com.pixflow.module.task.internal.progress.ProgressAggregator;
import com.pixflow.module.task.internal.recovery.HeartbeatWriter;
import com.pixflow.module.task.internal.scheduler.WorkUnitScheduler;
import com.pixflow.module.task.internal.terminal.TerminalStateJudge;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class TaskWorker {
  private final ProcessTaskMapper taskMapper;

  private final WorkerRouter router;

  private final WorkUnitScheduler scheduler;

  private final TerminalStateJudge terminalStateJudge;

  private final TaskLockManager lockManager;

  private final TaskMetrics metrics;

  private final TaskProperties properties;

  private final Clock clock;

  private final RecoveryCoordinator recoveryCoordinator;

  private final WorkUnitResultRepository resultRepository;

  private final ProgressAggregator progressAggregator;

  private final HeartbeatWriter heartbeatWriter;

  private final PublicationCoordinator publicationCoordinator;

  public TaskWorker(
      ProcessTaskMapper taskMapper,
      WorkerRouter router,
      WorkUnitScheduler scheduler,
      TerminalStateJudge terminalStateJudge,
      TaskLockManager lockManager,
      TaskMetrics metrics,
      TaskProperties properties,
      Clock clock,
      RecoveryCoordinator recoveryCoordinator,
      WorkUnitResultRepository resultRepository,
      ProgressAggregator progressAggregator,
      HeartbeatWriter heartbeatWriter,
      ProcessResultMapper resultMapper,
      ProcessResultMemberMapper memberMapper,
      GeneratedAssetPublicationPort publicationPort) {
    this.taskMapper = taskMapper;
    this.router = router;
    this.scheduler = scheduler;
    this.terminalStateJudge = terminalStateJudge;
    this.lockManager = lockManager;
    this.metrics = metrics;
    this.properties = properties;
    this.clock = clock;
    this.recoveryCoordinator = recoveryCoordinator;
    this.resultRepository = resultRepository;
    this.progressAggregator = progressAggregator;
    this.heartbeatWriter = heartbeatWriter;
    this.publicationCoordinator =
        new PublicationCoordinator(resultMapper, memberMapper, publicationPort, clock);
  }

  public void handle(TaskMessage message) {
    boolean accepted =
        lockManager.tryRunWithTaskLock(message.taskId(), guard -> runLocked(message, guard));
    if (!accepted) {
      metrics.recordLockContention();
    }
  }

  private void runLocked(TaskMessage message, com.pixflow.infra.cache.lock.LockGuard guard) {
    long taskId = Long.parseLong(message.taskId());
    ProcessTask task = taskMapper.selectById(taskId);
    if (task == null) {
      throw new PixFlowException(TaskErrorCode.TASK_NOT_FOUND, "task not found: " + taskId);
    }
    if (task.getStatus().terminal()) {
      return;
    }
    Instant now = clock.instant();
    int claimed =
        taskMapper.claimExecution(
            taskId, workerId(), now, now.minus(properties.getRecovery().getStaleAfter()));
    if (claimed != 1) {
      return;
    }
    task = taskMapper.selectById(taskId);
    ExecutionRun run = new ExecutionRun(message.taskId(), task.getRunEpoch(), guard);
    try (var heartbeat =
        heartbeatWriter.start(run, properties.getWorker().getHeartbeatInterval())) {
      // 恢复者必须先补齐既有 SUCCESS/PENDING，再决定哪些 Work Unit 需要重新计算。
      if (!publicationCoordinator.publishBacklog(task, run)) {
        run.deactivate();
        return;
      }
      List<WorkUnit> units = router.plan(task);
      var skippable = recoveryCoordinator.resolveSkippable(message.taskId()).succeeded();
      List<WorkUnit> pending =
          units.stream().filter(unit -> !skippable.contains(unit.unitKey())).toList();
      var futures = scheduler.submitAll(pending, unit -> router.execute(unit, run));
      var completions = new LinkedBlockingQueue<CompletableFuture<WorkUnitCompletion>>();
      futures.forEach(future -> future.whenComplete((ignored, failure) -> completions.add(future)));
      long deadline = System.nanoTime() + properties.getTerminal().getJudgeTimeout().toNanos();
      for (int completed = 0; completed < futures.size(); completed++) {
        WorkUnitCompletion completion;
        try {
          long remaining = deadline - System.nanoTime();
          var future = remaining > 0 ? completions.poll(remaining, TimeUnit.NANOSECONDS) : null;
          if (future == null) {
            throw new java.util.concurrent.TimeoutException("completion queue timed out");
          }
          completion = future.get();
        } catch (Exception e) {
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
          futures.forEach(item -> item.cancel(false));
          throw new PixFlowException(
              TaskErrorCode.TASK_WORKER_REJECTED, "task worker timed out", e);
        }
        run.assertCommitAllowed();
        var committed = resultRepository.commit(run, completion);
        if (committed == WorkUnitResultRepository.CommitResult.FENCED) {
          run.deactivate();
          futures.forEach(item -> item.cancel(false));
          return;
        }
        if (committed == WorkUnitResultRepository.CommitResult.APPLIED) {
          if (!publicationCoordinator.publishBacklog(task, run)) {
            run.deactivate();
            futures.forEach(item -> item.cancel(false));
            return;
          }
          publishProgress(completion, units.size());
        }
      }
      run.assertCommitAllowed();
      terminalStateJudge.judge(taskId, run.epoch());
      publicationCoordinator.markTaskFinished(taskId, clock.instant());
    }
  }

  private void publishProgress(WorkUnitCompletion completion, int total) {
    if (completion instanceof WorkUnitCompletion.Succeeded) {
      progressAggregator.success(completion.unit().taskId(), total);
    } else if (completion instanceof WorkUnitCompletion.Failed) {
      progressAggregator.failed(completion.unit().taskId(), total);
    } else {
      progressAggregator.skipped(completion.unit().taskId(), total);
    }
  }

  private static String workerId() {
    return java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
  }
}
