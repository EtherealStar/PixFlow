package com.pixflow.module.task.api.query;

import java.util.Map;

public record TaskFailureView(
    String code,
    String category,
    String recovery,
    String failedNodeId,
    String failedTool,
    int attemptCount,
    String safeMessage,
    Map<String, Object> details) {
  public TaskFailureView {
    details = details == null ? Map.of() : Map.copyOf(details);
  }
}
