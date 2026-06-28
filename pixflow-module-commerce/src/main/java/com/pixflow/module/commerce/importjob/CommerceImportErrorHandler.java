package com.pixflow.module.commerce.importjob;

import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.consumer.ConsumerErrorHandler;
import com.pixflow.infra.mq.consumer.RetryDecision;
import java.time.Duration;

public class CommerceImportErrorHandler implements ConsumerErrorHandler {
    @Override
    public RetryDecision onError(MessageEnvelope<?> envelope, Throwable error, int retryCount) {
        if (retryCount < 3) {
            return new RetryDecision.Retry(Duration.ofSeconds(5L * (retryCount + 1)), "commerce import retry");
        }
        return new RetryDecision.DeadLetter("commerce import failed: " + error.getMessage());
    }
}
