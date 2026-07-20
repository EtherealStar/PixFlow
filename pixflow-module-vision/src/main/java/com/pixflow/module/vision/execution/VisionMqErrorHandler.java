package com.pixflow.module.vision.execution;

import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.consumer.ConsumerErrorHandler;
import com.pixflow.infra.mq.consumer.RetryDecision;
import java.time.Duration;

public final class VisionMqErrorHandler implements ConsumerErrorHandler {
    @Override
    public RetryDecision onError(MessageEnvelope<?> envelope, Throwable error, int retryCount) {
        if (retryCount < 8) {
            long seconds = Math.min(60L, 1L << Math.min(6, retryCount));
            return new RetryDecision.Retry(Duration.ofSeconds(seconds), "vision message retry");
        }
        return new RetryDecision.DeadLetter(
                "vision message failed: " + Sanitizer.sanitizeMessage(error.getMessage()));
    }
}
