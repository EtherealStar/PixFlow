package com.pixflow.module.commerce.importjob;

import com.pixflow.infra.mq.topology.QueueTopology;
import com.pixflow.infra.mq.topology.QueueTopologyBuilder;

public final class CommerceImportTopology {
    public static final String EXCHANGE = "pixflow.commerce.import";
    public static final String QUEUE = "pixflow.commerce.import.q";
    public static final String ROUTING_KEY = "commerce.import";
    public static final String DLX = "pixflow.commerce.import.dlx";
    public static final String DLQ = "pixflow.commerce.import.dlq";
    public static final String DEAD_ROUTING_KEY = "commerce.import.dead";
    public static final String RETRY_QUEUE = "pixflow.commerce.import.retry.q";
    public static final String RETRY_ROUTING_KEY = "commerce.import.retry";

    private CommerceImportTopology() {
    }

    public static QueueTopology topology() {
        return QueueTopologyBuilder.direct(EXCHANGE)
                .queue(QUEUE)
                .routingKey(ROUTING_KEY)
                .deadLetter(DLX, DLQ, DEAD_ROUTING_KEY)
                .retry(RETRY_QUEUE, RETRY_ROUTING_KEY)
                .build();
    }
}
