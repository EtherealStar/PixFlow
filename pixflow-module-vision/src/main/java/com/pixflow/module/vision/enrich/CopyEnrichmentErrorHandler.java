package com.pixflow.module.vision.enrich;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.consumer.ConsumerErrorHandler;
import com.pixflow.infra.mq.consumer.RetryDecision;
import java.time.Duration;

public class CopyEnrichmentErrorHandler implements ConsumerErrorHandler {
    @Override
    public RetryDecision onError(MessageEnvelope<?> envelope, Throwable error, int retryCount) {
        if (error instanceof PixFlowException pixFlowException) {
            RecoveryHint recovery = pixFlowException.recovery();
            if (recovery == RecoveryHint.SKIP) {
                return new RetryDecision.AckDrop(pixFlowException.getMessage());
            }
            if (recovery == RecoveryHint.TERMINATE) {
                return new RetryDecision.DeadLetter(pixFlowException.getMessage());
            }
        }
        return new RetryDecision.Retry(Duration.ofSeconds(Math.min(300, 5L * (retryCount + 1))), safeReason(error));
    }

    private String safeReason(Throwable error) {
        return error == null || error.getMessage() == null ? "vision copy enrichment failed" : error.getMessage();
    }
}
