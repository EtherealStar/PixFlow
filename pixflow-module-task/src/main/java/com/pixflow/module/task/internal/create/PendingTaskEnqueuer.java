package com.pixflow.module.task.internal.create;

import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.infra.mq.TaskMessage;
import com.pixflow.module.task.infra.mq.TaskMessagePublisher;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;

public class PendingTaskEnqueuer {
  private final ProcessTaskMapper taskMapper;

  private final TaskMessagePublisher publisher;

  private final TaskProperties properties;

  private final Clock clock;

  public PendingTaskEnqueuer(
      ProcessTaskMapper taskMapper,
      TaskMessagePublisher publisher,
      TaskProperties properties,
      Clock clock) {
    this.taskMapper = taskMapper;
    this.publisher = publisher;
    this.properties = properties;
    this.clock = clock;
  }

  public void enqueue(long taskId) {
    ProcessTask task = taskMapper.selectById(taskId);
    if (task == null || task.getStatus() != TaskStatus.PENDING) {
      return;
    }
    if (taskMapper.transit(taskId, TaskStatus.PENDING, TaskStatus.QUEUED, clock.instant()) != 1) {
      return;
    }
    try {
      publisher.publish(
          new TaskMessage(
              Long.toString(taskId),
              task.getTaskType(),
              task.getPriority() == null ? 0 : task.getPriority(),
              task.getSchemaVersion()));
    } catch (RuntimeException failure) {
      // MQ 未接收时恢复为 PENDING，后续扫描可安全重试入队。
      taskMapper.resetFailedEnqueue(taskId, clock.instant());
      throw failure;
    }
  }

  @Scheduled(cron = "${pixflow.task.recovery.cron:0 */1 * * * *}")
  public void retryPending() {
    for (ProcessTask task :
        taskMapper.findByStatus(TaskStatus.PENDING, properties.getRecovery().getScanLimit())) {
      try {
        enqueue(task.getId());
      } catch (RuntimeException ignored) {
        // 单个 MQ 故障不能阻止其它 PENDING task 获得入队机会。
      }
    }
  }
}
