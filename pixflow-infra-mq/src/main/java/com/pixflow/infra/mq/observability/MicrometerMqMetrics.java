package com.pixflow.infra.mq.observability;

import com.pixflow.infra.mq.PublishFailureType;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public class MicrometerMqMetrics implements MqMetrics {
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, AtomicLong> dlqDepthGauges = new ConcurrentHashMap<>();

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
        DistributionSummary.builder("pixflow.mq.retry.count")
                .tags(tags(null, queue, null))
                .register(meterRegistry)
                .record(Math.max(0, retryCount));
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
        AtomicLong gauge = dlqDepthGauges.computeIfAbsent(queue, ignored -> {
            AtomicLong value = new AtomicLong();
            meterRegistry.gauge("pixflow.mq.dlq.depth", tags(null, queue, null), value);
            return value;
        });
        gauge.set(Math.max(0L, depth));
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
