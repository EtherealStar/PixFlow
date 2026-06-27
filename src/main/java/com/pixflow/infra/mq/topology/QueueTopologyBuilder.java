package com.pixflow.infra.mq.topology;

import java.util.LinkedHashMap;
import java.util.Map;

public final class QueueTopologyBuilder {
    private final String exchange;
    private final String exchangeType;
    private String queue;
    private String routingKey;
    private String deadLetterExchange;
    private String deadLetterQueue;
    private String deadLetterRoutingKey;
    private String retryQueue;
    private String retryRoutingKey;
    private boolean retryEnabled;
    private final Map<String, Object> queueArguments = new LinkedHashMap<>();

    private QueueTopologyBuilder(String exchange, String exchangeType) {
        this.exchange = exchange;
        this.exchangeType = exchangeType;
    }

    public static QueueTopologyBuilder direct(String exchange) {
        return new QueueTopologyBuilder(exchange, "direct");
    }

    public QueueTopologyBuilder queue(String queue) {
        this.queue = queue;
        return this;
    }

    public QueueTopologyBuilder routingKey(String routingKey) {
        this.routingKey = routingKey;
        return this;
    }

    public QueueTopologyBuilder deadLetter(
            String deadLetterExchange,
            String deadLetterQueue,
            String deadLetterRoutingKey) {
        this.deadLetterExchange = deadLetterExchange;
        this.deadLetterQueue = deadLetterQueue;
        this.deadLetterRoutingKey = deadLetterRoutingKey;
        return this;
    }

    public QueueTopologyBuilder retry(String retryQueue, String retryRoutingKey) {
        this.retryQueue = retryQueue;
        this.retryRoutingKey = retryRoutingKey;
        this.retryEnabled = true;
        return this;
    }

    public QueueTopologyBuilder queueArgument(String key, Object value) {
        this.queueArguments.put(key, value);
        return this;
    }

    public QueueTopology build() {
        return new QueueTopology(
                exchange,
                exchangeType,
                queue,
                routingKey,
                deadLetterExchange,
                deadLetterQueue,
                deadLetterRoutingKey,
                retryQueue,
                retryRoutingKey,
                retryEnabled,
                queueArguments);
    }
}
