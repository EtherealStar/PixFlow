package com.pixflow.infra.mq.consumer;

import com.pixflow.infra.mq.MessageEnvelope;

@FunctionalInterface
public interface ManagedMessageHandler<T> {
    void handle(MessageEnvelope<T> envelope) throws Exception;
}
