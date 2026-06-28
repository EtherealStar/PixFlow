package com.pixflow.infra.mq;

import java.util.Objects;

public record PublishFailure(
        PublishFailureType type,
        String reason,
        Integer replyCode,
        String returnedExchange,
        String returnedRoutingKey) {
    public PublishFailure {
        Objects.requireNonNull(type, "type must not be null");
        if (reason == null || reason.isBlank()) {
            reason = type.name();
        }
    }
}
