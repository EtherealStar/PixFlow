package com.pixflow.module.task.internal.recovery;

import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.internal.worker.ExecutionRun;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HeartbeatWriter implements AutoCloseable {
  private final ProcessTaskMapper taskMapper;

  private final Clock clock;

  private final TaskMetrics metrics;

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "pixflow-task-heartbeat");
            thread.setDaemon(true);
            return thread;
          });

  public HeartbeatWriter(ProcessTaskMapper taskMapper, Clock clock, TaskMetrics metrics) {
    this.taskMapper = taskMapper;
    this.clock = clock;
    this.metrics = metrics;
  }

  public HeartbeatSession start(ExecutionRun run, Duration interval) {
    var future =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                if (taskMapper.heartbeatEpoch(
                        Long.parseLong(run.taskId()), run.epoch(), clock.instant())
                    != 1) {
                  metrics.recordHeartbeat("stale");
                  run.deactivate();
                } else {
                  metrics.recordHeartbeat("ok");
                }
              } catch (RuntimeException failure) {
                // 无法证明 epoch 仍有效时 fail-closed，owner 不再提交 completion。
                metrics.recordHeartbeat("error");
                run.deactivate();
              }
            },
            interval.toMillis(),
            interval.toMillis(),
            TimeUnit.MILLISECONDS);
    return () -> future.cancel(false);
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }

  public interface HeartbeatSession extends AutoCloseable {
    @Override
    void close();
  }
}
