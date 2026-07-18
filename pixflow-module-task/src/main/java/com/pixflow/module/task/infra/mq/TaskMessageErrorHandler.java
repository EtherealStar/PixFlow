package com.pixflow.module.task.infra.mq;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.consumer.ConsumerErrorHandler;
import com.pixflow.infra.mq.consumer.RetryDecision;
import java.time.Duration;

public class TaskMessageErrorHandler implements ConsumerErrorHandler {
  @Override
  public RetryDecision onError(MessageEnvelope<?> envelope, Throwable error, int retryCount) {
    if (error instanceof PixFlowException pixFlowException) {
      if (pixFlowException.recovery() == RecoveryHint.SKIP) {
        return new RetryDecision.AckDrop(pixFlowException.getMessage());
      }
      if (pixFlowException.recovery() == RecoveryHint.TERMINATE) {
        return new RetryDecision.DeadLetter(pixFlowException.getMessage());
      }
    }
    return new RetryDecision.Retry(
        Duration.ofSeconds(Math.min(300, 5L * (retryCount + 1))),
        error == null ? "task message failed" : error.getMessage());
  }
}
