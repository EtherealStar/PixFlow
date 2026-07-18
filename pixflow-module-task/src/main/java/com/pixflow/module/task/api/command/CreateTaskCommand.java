package com.pixflow.module.task.api.command;

import com.pixflow.module.task.domain.model.TaskType;
import java.util.Objects;

public record CreateTaskCommand(
    TaskType taskType,
    String conversationId,
    long packageId,
    String idempotencyKey,
    String payload,
    int priority,
    int expectedCount,
    String payloadHash) {

  public CreateTaskCommand {
    taskType = Objects.requireNonNull(taskType, "taskType");
    conversationId = requireText(conversationId, "conversationId");
    if (packageId <= 0) {
      throw new IllegalArgumentException("packageId must be positive");
    }
    idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
    payload = requireText(payload, "payload");
    if (expectedCount < 0) {
      throw new IllegalArgumentException("expectedCount must not be negative");
    }
    payloadHash = payloadHash == null ? null : payloadHash.trim();
  }

  public CreateTaskCommand(
      TaskType taskType,
      String conversationId,
      long packageId,
      String idempotencyKey,
      String payload) {
    this(taskType, conversationId, packageId, idempotencyKey, payload, 0, 0, null);
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value.trim();
  }
}
