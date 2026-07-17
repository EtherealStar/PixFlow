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
    public void recordPublishConfirmed(String topic, String tag) {
        meterRegistry.counter("pixflow.mq.publish", tags(topic, tag, null).and("result", "confirmed")).increment();
    }

    @Override
    public void recordPublishFailed(String topic, String tag, PublishFailureType failureType) {
        meterRegistry.counter(
                        "pixflow.mq.publish",
                        tags(topic, tag, null).and(
                                "result", "failed", "failureType", failureType.name()))
                .increment();
    }

    @Override
    public void recordConsumeAck(String topic, String consumerGroup) {
        meterRegistry.counter("pixflow.mq.consume", tags(topic, null, consumerGroup).and("result", "ack")).increment();
    }

    @Override
    public void recordConsumeRetry(String topic, String consumerGroup, int retryCount) {
        meterRegistry.counter(
                        "pixflow.mq.consume",
                        tags(topic, null, consumerGroup).and("result", "retry"))
                .increment();
        DistributionSummary.builder("pixflow.mq.retry.count")
                .tags(tags(topic, null, consumerGroup))
                .register(meterRegistry)
                .record(Math.max(0, retryCount));
    }

    @Override
    public void recordConsumeDeadLetter(String topic, String consumerGroup) {
        meterRegistry.counter(
                        "pixflow.mq.consume",
                        tags(topic, null, consumerGroup).and("result", "dead_letter"))
                .increment();
    }

    @Override
    public void recordConsumeAckDrop(String topic, String consumerGroup) {
        meterRegistry.counter(
                        "pixflow.mq.consume",
                        tags(topic, null, consumerGroup).and("result", "ack_drop"))
                .increment();
    }

    @Override
    public void recordDlqDepth(String topic, String consumerGroup, long depth) {
        String key = topic + ":" + consumerGroup;
        AtomicLong gauge = dlqDepthGauges.computeIfAbsent(key, ignored -> {
            AtomicLong value = new AtomicLong();
            meterRegistry.gauge("pixflow.mq.dlq.depth", tags(topic, null, consumerGroup), value);
            return value;
        });
        gauge.set(Math.max(0L, depth));
    }

    private Tags tags(String topic, String tag, String consumerGroup) {
        Tags tags = Tags.empty();
        if (topic != null) {
            tags = tags.and("topic", topic);
        }
        if (tag != null) {
            tags = tags.and("tag", tag);
        }
        if (consumerGroup != null) {
            tags = tags.and("consumerGroup", consumerGroup);
        }
        return tags;
    }
}
