package com.pixflow.module.task.api.command;

public record RetryFailedTaskCommand(TaskId sourceTaskId, String idempotencyKey) {
  public RetryFailedTaskCommand {
    if (sourceTaskId == null) {
      throw new IllegalArgumentException("sourceTaskId must not be null");
    }
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must not be blank");
    }
    idempotencyKey = idempotencyKey.trim();
  }
}
