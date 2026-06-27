package com.pixflow.infra.mq;

public record PublishResult(
        boolean confirmed,
        PublishFailure failure,
        String exchange,
        String routingKey,
        String correlationId) {

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
