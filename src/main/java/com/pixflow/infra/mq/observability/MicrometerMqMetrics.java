package com.pixflow.infra.mq.observability;

import com.pixflow.infra.mq.PublishFailureType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

public class MicrometerMqMetrics implements MqMetrics {
    private final MeterRegistry meterRegistry;

    public MicrometerMqMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordPublishConfirmed(String exchange, String routingKey) {
        meterRegistry.counter("pixflow.mq.publish", tags(exchange, null, routingKey)
                .and("result", "confirmed")).increment();
    }

    @Override
    public void recordPublishFailed(String exchange, String routingKey, PublishFailureType failureType) {
        meterRegistry.counter("pixflow.mq.publish", tags(exchange, null, routingKey)
                .and("result", "failed", "failureType", failureType.name())).increment();
    }

    @Override
    public void recordConsumeAck(String queue) {
        meterRegistry.counter("pixflow.mq.consume", tags(null, queue, null).and("result", "ack")).increment();
    }

    @Override
    public void recordConsumeRetry(String queue, int retryCount) {
        meterRegistry.counter("pixflow.mq.consume", tags(null, queue, null).and("result", "retry")).increment();
        meterRegistry.counter("pixflow.mq.retry.count", tags(null, queue, null).and("retryCount", String.valueOf(retryCount))).increment();
    }

    @Override
    public void recordConsumeDeadLetter(String queue) {
        meterRegistry.counter("pixflow.mq.consume", tags(null, queue, null).and("result", "dead_letter")).increment();
    }

    @Override
    public void recordConsumeAckDrop(String queue) {
        meterRegistry.counter("pixflow.mq.consume", tags(null, queue, null).and("result", "ack_drop")).increment();
    }

    @Override
    public void recordDlqDepth(String queue, long depth) {
        meterRegistry.gauge("pixflow.mq.dlq.depth", tags(null, queue, null), depth);
    }

    private Tags tags(String exchange, String queue, String routingKey) {
        Tags tags = Tags.empty();
        if (exchange != null) {
            tags = tags.and("exchange", exchange);
        }
        if (queue != null) {
            tags = tags.and("queue", queue);
        }
        if (routingKey != null) {
            tags = tags.and("routingKey", routingKey);
        }
        return tags;
    }
}
