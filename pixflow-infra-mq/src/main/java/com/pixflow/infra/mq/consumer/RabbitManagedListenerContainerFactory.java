package com.pixflow.infra.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.infra.mq.config.MqProperties;
import com.pixflow.infra.mq.observability.MqMetrics;
import com.pixflow.infra.mq.topology.QueueTopology;
import com.pixflow.infra.mq.trace.TraceHeaderPropagator;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

public class RabbitManagedListenerContainerFactory implements ManagedListenerContainerFactory {
    private final ConnectionFactory connectionFactory;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final ErrorNormalizer errorNormalizer;
    private final TraceHeaderPropagator traceHeaderPropagator;
    private final MqMetrics metrics;
    private final MqProperties properties;

    public RabbitManagedListenerContainerFactory(
            ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            ErrorNormalizer errorNormalizer,
            TraceHeaderPropagator traceHeaderPropagator,
            MqMetrics metrics,
            MqProperties properties) {
        this.connectionFactory = connectionFactory;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.errorNormalizer = errorNormalizer;
        this.traceHeaderPropagator = traceHeaderPropagator;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public MessageListenerContainer create(
            QueueTopology topology,
            Class<?> payloadType,
            ManagedMessageHandler<?> handler,
            ConsumerErrorHandler errorHandler) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(topology.queue());
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(properties.getPrefetch());
        container.setConcurrentConsumers(properties.getConsumerConcurrency());
        container.setMessageListener(newListener(topology, payloadType, handler, errorHandler));
        return container;
    }

    private <T> ManagedMessageListener<T> newListener(
            QueueTopology topology,
            Class<?> payloadType,
            ManagedMessageHandler<?> handler,
            ConsumerErrorHandler errorHandler) {
        @SuppressWarnings("unchecked")
        Class<T> typedPayloadType = (Class<T>) payloadType;
        @SuppressWarnings("unchecked")
        ManagedMessageHandler<T> typedHandler = (ManagedMessageHandler<T>) handler;
        return new ManagedMessageListener<>(
                topology,
                typedPayloadType,
                typedHandler,
                errorHandler,
                rabbitTemplate,
                objectMapper,
                errorNormalizer,
                traceHeaderPropagator,
                metrics,
                properties);
    }
}
