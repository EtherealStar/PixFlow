package com.pixflow.infra.mq.consumer;

import com.pixflow.infra.mq.MessageEnvelope;

public interface ConsumerErrorHandler {
    RetryDecision onError(MessageEnvelope<?> envelope, Throwable error, int retryCount);
}
