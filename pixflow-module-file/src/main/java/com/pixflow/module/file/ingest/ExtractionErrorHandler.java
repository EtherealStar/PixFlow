package com.pixflow.module.file.ingest;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.consumer.ConsumerErrorHandler;
import com.pixflow.infra.mq.consumer.RetryDecision;
import java.time.Duration;

public class ExtractionErrorHandler implements ConsumerErrorHandler {
    @Override
    public RetryDecision onError(MessageEnvelope<?> envelope, Throwable error, int retryCount) {
        if (error instanceof PixFlowException pixFlowException
                && pixFlowException.recovery() == RecoveryHint.TERMINATE) {
            return new RetryDecision.DeadLetter(pixFlowException.getMessage());
        }
        return new RetryDecision.Retry(Duration.ofSeconds(Math.min(300, 5L * (retryCount + 1))), error.getMessage());
    }
}
