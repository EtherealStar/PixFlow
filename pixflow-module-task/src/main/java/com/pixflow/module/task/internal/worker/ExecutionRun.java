package com.pixflow.module.task.internal.worker;

import com.pixflow.infra.cache.lock.LockGuard;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** task owner 持有的一次执行代际；子线程只能读取 epoch，不能提交数据库。 */
public final class ExecutionRun {
  private final String taskId;

  private final long epoch;

  private final LockGuard lockGuard;

  private final AtomicBoolean active = new AtomicBoolean(true);

  public ExecutionRun(String taskId, long epoch, LockGuard lockGuard) {
    this.taskId = Objects.requireNonNull(taskId, "taskId");
    if (epoch <= 0) {
      throw new IllegalArgumentException("epoch must be positive");
    }
    this.epoch = epoch;
    this.lockGuard = Objects.requireNonNull(lockGuard, "lockGuard");
  }

  public String taskId() {
    return taskId;
  }

  public long epoch() {
    return epoch;
  }

  public void deactivate() {
    active.set(false);
  }

  public void assertCommitAllowed() {
    if (!active.get()) {
      throw new IllegalStateException("execution epoch 已失效: " + epoch);
    }
    lockGuard.assertHeld();
  }
}
