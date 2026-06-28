package com.pixflow.module.file.ingest;

import com.pixflow.infra.mq.topology.QueueTopology;
import com.pixflow.infra.mq.topology.QueueTopologyBuilder;

public final class ExtractionTopology {
    public static final String EXCHANGE = "pixflow.file";
    public static final String QUEUE = "pixflow.file.q";
    public static final String ROUTING_KEY = "file.extract";
    public static final String DLX = "pixflow.file.dlx";
    public static final String DLQ = "pixflow.file.dlq";
    public static final String DEAD_ROUTING_KEY = "file.dead";
    public static final String RETRY_QUEUE = "pixflow.file.retry.q";
    public static final String RETRY_ROUTING_KEY = "file.retry";

    private ExtractionTopology() {
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
