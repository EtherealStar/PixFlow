package com.pixflow.infra.mq;

import java.util.Objects;

public record PublishResult(
        boolean confirmed,
        PublishFailure failure,
        String exchange,
        String routingKey,
        String correlationId) {
    public PublishResult {
        if (confirmed && failure != null) {
            throw new IllegalArgumentException("confirmed result must not carry failure");
        }
        if (!confirmed && failure == null) {
            throw new IllegalArgumentException("failed result must carry failure");
        }
        Objects.requireNonNull(exchange, "exchange must not be null");
        Objects.requireNonNull(routingKey, "routingKey must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
    }

    public static PublishResult confirmed(String exchange, String routingKey, String correlationId) {
        return new PublishResult(true, null, exchange, routingKey, correlationId);
    }

    public static PublishResult failed(
            String exchange,
            String routingKey,
            String correlationId,
            PublishFailure failure) {
        return new PublishResult(false, failure, exchange, routingKey, correlationId);
    }

    public boolean failed() {
        return !confirmed;
    }
}
