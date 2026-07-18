package com.pixflow.module.task.infra.mq;

import com.pixflow.module.task.domain.model.TaskType;

public record TaskMessage(String taskId, TaskType taskType, int priority, String schemaVersion) {
  public TaskMessage {
    if (taskId == null || taskId.isBlank()) {
      throw new IllegalArgumentException("taskId must not be blank");
    }
    if (taskType == null) {
      throw new IllegalArgumentException("taskType must not be null");
    }
    schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "1.0" : schemaVersion.trim();
  }
}
