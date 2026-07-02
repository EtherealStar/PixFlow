package com.pixflow.infra.mq.rocket;

import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.infra.mq.config.MqProperties;
import com.pixflow.infra.mq.consumer.ConsumerErrorHandler;
import com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory;
import com.pixflow.infra.mq.consumer.ManagedMessageContainer;
import com.pixflow.infra.mq.consumer.ManagedMessageHandler;
import com.pixflow.infra.mq.consumer.ManagedMessageListener;
import com.pixflow.infra.mq.destination.ConsumerBinding;
import com.pixflow.infra.mq.observability.MqMetrics;
import com.pixflow.infra.mq.trace.TraceHeaderPropagator;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;

public class RocketManagedListenerContainerFactory implements ManagedListenerContainerFactory {
    private final MqProperties properties;
    private final RocketMessageCodec codec;
    private final ErrorNormalizer errorNormalizer;
    private final TraceHeaderPropagator traceHeaderPropagator;
    private final MqMetrics metrics;

    public RocketManagedListenerContainerFactory(MqProperties properties, RocketMessageCodec codec, ErrorNormalizer errorNormalizer,
            TraceHeaderPropagator traceHeaderPropagator, MqMetrics metrics) {
        this.properties = properties;
        this.codec = codec;
        this.errorNormalizer = errorNormalizer;
        this.traceHeaderPropagator = traceHeaderPropagator;
        this.metrics = metrics;
    }

    @Override
    public ManagedMessageContainer create(ConsumerBinding binding, ManagedMessageHandler<?> handler, ConsumerErrorHandler errorHandler) {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(binding.consumerGroup());
        consumer.setNamesrvAddr(properties.getNamesrvAddr());
        consumer.setConsumeThreadMin(properties.getConsumer().getConsumeThreadMin());
        consumer.setConsumeThreadMax(properties.getConsumer().getConsumeThreadMax());
        consumer.setConsumeTimeout(Math.max(1L, properties.getConsumer().getConsumeTimeout().toMinutes()));
        return new RocketManagedMessageContainer(consumer, binding, newListener(binding, handler, errorHandler));
    }

    private <T> ManagedMessageListener<T> newListener(ConsumerBinding binding, ManagedMessageHandler<?> handler, ConsumerErrorHandler errorHandler) {
        @SuppressWarnings("unchecked") Class<T> typedPayloadType = (Class<T>) binding.payloadType();
        @SuppressWarnings("unchecked") ManagedMessageHandler<T> typedHandler = (ManagedMessageHandler<T>) handler;
        return new ManagedMessageListener<>(binding, typedPayloadType, typedHandler, errorHandler, codec, errorNormalizer, traceHeaderPropagator, metrics, properties);
    }
}
