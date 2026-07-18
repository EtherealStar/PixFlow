package com.pixflow.module.task.domain.model;

public enum TaskStatus {
  PENDING,
  QUEUED,
  RUNNING,
  COMPLETED,
  FAILED,
  CANCELLED,
  PARTIAL;

  public boolean terminal() {
    return this == COMPLETED || this == FAILED || this == CANCELLED || this == PARTIAL;
  }
}
