package com.pixflow.module.task.api.command;

public record RetryFailedTaskCommand(TaskId sourceTaskId) {
  public RetryFailedTaskCommand {
    if (sourceTaskId == null) {
      throw new IllegalArgumentException("sourceTaskId must not be null");
    }
  }
}
