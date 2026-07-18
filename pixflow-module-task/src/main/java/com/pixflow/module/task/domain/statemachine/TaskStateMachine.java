package com.pixflow.module.task.domain.statemachine;

import com.pixflow.module.task.domain.model.TaskStatus;
import java.util.Map;
import java.util.Set;

public final class TaskStateMachine {
  public static final TaskStateMachine INSTANCE = new TaskStateMachine();

  private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED =
      Map.of(
          TaskStatus.PENDING, Set.of(TaskStatus.QUEUED, TaskStatus.FAILED),
          TaskStatus.QUEUED, Set.of(TaskStatus.RUNNING, TaskStatus.CANCELLED, TaskStatus.FAILED),
          TaskStatus.RUNNING,
              Set.of(
                  TaskStatus.COMPLETED,
                  TaskStatus.FAILED,
                  TaskStatus.CANCELLED,
                  TaskStatus.PARTIAL),
          TaskStatus.COMPLETED, Set.of(),
          TaskStatus.FAILED, Set.of(),
          TaskStatus.CANCELLED, Set.of(),
          TaskStatus.PARTIAL, Set.of());

  private TaskStateMachine() { }

  public boolean canTransit(TaskStatus from, TaskStatus to) {
    return ALLOWED.getOrDefault(from, Set.of()).contains(to);
  }

  public void verify(TaskStatus from, TaskStatus to) {
    if (!canTransit(from, to)) {
      throw new IllegalStateTransitionException(from, to);
    }
  }
}
