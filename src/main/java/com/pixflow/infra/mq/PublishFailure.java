package com.pixflow.infra.mq;

public record PublishFailure(
        PublishFailureType type,
        String reason,
        Integer replyCode,
        String returnedExchange,
        String returnedRoutingKey) {
}
