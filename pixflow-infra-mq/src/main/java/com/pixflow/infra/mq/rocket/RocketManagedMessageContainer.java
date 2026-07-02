package com.pixflow.infra.mq.rocket;

import com.pixflow.infra.mq.consumer.ManagedMessageContainer;
import com.pixflow.infra.mq.consumer.ManagedMessageListener;
import com.pixflow.infra.mq.destination.ConsumerBinding;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;

public class RocketManagedMessageContainer implements ManagedMessageContainer {
    private final DefaultMQPushConsumer consumer;
    private final ConsumerBinding binding;
    private final ManagedMessageListener<?> listener;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RocketManagedMessageContainer(DefaultMQPushConsumer consumer, ConsumerBinding binding, ManagedMessageListener<?> listener) {
        this.consumer = consumer;
        this.binding = binding;
        this.listener = listener;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        try {
            consumer.subscribe(binding.topic(), binding.tagExpression());
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                for (MessageExt message : messages) {
                    ManagedMessageListener.ListenerResult result = listener.onMessage(toInbound(message));
                    if (result == ManagedMessageListener.ListenerResult.RECONSUME_LATER) return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();
        } catch (Exception ex) {
            running.set(false);
            throw new IllegalStateException("RocketMQ consumer start failed for " + binding, ex);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) consumer.shutdown();
    }

    @Override
    public boolean isRunning() { return running.get(); }

    private ManagedMessageListener.InboundMessage toInbound(MessageExt message) {
        Map<String, Object> headers = new LinkedHashMap<>();
        if (message.getProperties() != null) headers.putAll(message.getProperties());
        return new ManagedMessageListener.InboundMessage(message.getBody(), headers, message.getReconsumeTimes(), message.getMsgId());
    }
}
