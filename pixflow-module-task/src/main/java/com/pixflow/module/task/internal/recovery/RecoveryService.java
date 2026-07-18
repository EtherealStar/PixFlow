package com.pixflow.module.task.internal.recovery;

import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.mq.TaskMessage;
import com.pixflow.module.task.infra.mq.TaskMessagePublisher;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;

public class RecoveryService {
  private final ProcessTaskMapper taskMapper;

  private final TaskMessagePublisher publisher;

  private final TaskProperties properties;

  private final TaskMetrics metrics;

  private final Clock clock;

  public RecoveryService(
      ProcessTaskMapper taskMapper,
      TaskMessagePublisher publisher,
      TaskProperties properties,
      TaskMetrics metrics,
      Clock clock) {
    this.taskMapper = taskMapper;
    this.publisher = publisher;
    this.properties = properties;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Scheduled(cron = "${pixflow.task.recovery.cron:0 */1 * * * *}")
  public void scan() {
    Instant staleBefore = clock.instant().minus(properties.getRecovery().getStaleAfter());
    // 恢复扫描只依据 MySQL heartbeat 发现并重投，不读取锁、不修改 epoch、更不解锁。
    List<ProcessTask> running =
        taskMapper.findStaleRunning(staleBefore, properties.getRecovery().getScanLimit());
    for (ProcessTask task : running) {
      try {
        publisher.publish(
            new TaskMessage(
                task.getId().toString(),
                task.getTaskType(),
                task.getPriority() == null ? 0 : task.getPriority(),
                task.getSchemaVersion()));
        metrics.recordRecovery("requeued");
      } catch (RuntimeException failure) {
        metrics.recordRecovery("error");
      }
    }
  }
}
