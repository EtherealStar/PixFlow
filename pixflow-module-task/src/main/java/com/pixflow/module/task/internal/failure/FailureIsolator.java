package com.pixflow.module.task.internal.failure;

import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.WorkUnit;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.internal.worker.WorkUnitCompletion;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

public class FailureIsolator {
  private final ErrorNormalizer normalizer;

  private final TaskMetrics metrics;

  private final Clock clock;

  public FailureIsolator(ErrorNormalizer normalizer, TaskMetrics metrics, Clock clock) {
    this.normalizer = normalizer;
    this.metrics = metrics;
    this.clock = clock;
  }

  public WorkUnitCompletion.Failed isolate(
      WorkUnit unit, Throwable throwable, long runEpoch, Instant startedAt) {
    PixFlowException normalized = normalizer.normalize(throwable);
    metrics.recordFailure(
        normalized.code() == null ? TaskErrorCode.TASK_RESULT_WRITE_FAILED : normalized.code());
    Map<String, Object> details = sanitizedDetails(normalized.details());
    return new WorkUnitCompletion.Failed(
        unit,
        runEpoch,
        startedAt,
        clock.instant(),
        normalized.code().code(),
        normalized.category().name(),
        normalized.recovery().name(),
        Sanitizer.sanitizeMessage(normalized.getMessage()),
        text(details.get("failedNodeId")),
        text(details.get("failedTool")),
        positiveInt(details.get("attemptCount")),
        details);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> sanitizedDetails(Map<String, Object> details) {
    if (details == null || details.isEmpty()) {
      return Map.of();
    }
    return Map.copyOf((Map<String, Object>) Sanitizer.sanitizeTraceValue("details", details));
  }

  private static String text(Object value) {
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  private static int positiveInt(Object value) {
    return value instanceof Number number && number.intValue() > 0 ? number.intValue() : 1;
  }
}
