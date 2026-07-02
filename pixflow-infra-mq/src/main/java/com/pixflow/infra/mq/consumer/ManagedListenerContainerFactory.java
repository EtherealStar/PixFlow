package com.pixflow.infra.mq.consumer;

import com.pixflow.infra.mq.destination.ConsumerBinding;

public interface ManagedListenerContainerFactory {
    ManagedMessageContainer create(
            ConsumerBinding binding,
            ManagedMessageHandler<?> handler,
            ConsumerErrorHandler errorHandler);
}
