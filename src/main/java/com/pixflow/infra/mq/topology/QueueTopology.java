package com.pixflow.infra.mq.topology;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.Assert;

public record QueueTopology(
        String exchange,
        String exchangeType,
        String queue,
        String routingKey,
        String deadLetterExchange,
        String deadLetterQueue,
        String deadLetterRoutingKey,
        String retryQueue,
        String retryRoutingKey,
        boolean retryEnabled,
        Map<String, Object> queueArguments) {

    public QueueTopology {
        Assert.hasText(exchange, "exchange must not be blank");
        Assert.hasText(exchangeType, "exchangeType must not be blank");
        Assert.hasText(queue, "queue must not be blank");
        Assert.hasText(routingKey, "routingKey must not be blank");
        Assert.hasText(deadLetterExchange, "deadLetterExchange must not be blank");
        Assert.hasText(deadLetterQueue, "deadLetterQueue must not be blank");
        Assert.hasText(deadLetterRoutingKey, "deadLetterRoutingKey must not be blank");
        if (retryEnabled) {
            Assert.hasText(retryQueue, "retryQueue must not be blank when retry is enabled");
            Assert.hasText(retryRoutingKey, "retryRoutingKey must not be blank when retry is enabled");
        }
        queueArguments = queueArguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(queueArguments));
    }
}
