package com.pixflow.infra.mq.consumer;

import com.pixflow.infra.mq.topology.QueueTopology;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;

public interface ManagedListenerContainerFactory {
    MessageListenerContainer create(
            QueueTopology topology,
            Class<?> payloadType,
            ManagedMessageHandler<?> handler,
            ConsumerErrorHandler errorHandler);
}
