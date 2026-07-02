package com.pixflow.infra.mq.destination;

import org.springframework.util.Assert;

public record ConsumerBinding(
        String topic,
        String tagExpression,
        String consumerGroup,
        Class<?> payloadType) {
    public ConsumerBinding {
        Assert.hasText(topic, "topic must not be blank");
        Assert.hasText(tagExpression, "tagExpression must not be blank");
        Assert.hasText(consumerGroup, "consumerGroup must not be blank");
        Assert.notNull(payloadType, "payloadType must not be null");
    }

    public static ConsumerBinding of(String topic, String tagExpression, String consumerGroup, Class<?> payloadType) {
        return new ConsumerBinding(topic, tagExpression, consumerGroup, payloadType);
    }
}
