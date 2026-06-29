package com.pixflow.module.vision.enrich;

import com.pixflow.infra.mq.topology.QueueTopology;
import com.pixflow.infra.mq.topology.QueueTopologyBuilder;

public final class CopyEnrichmentTopology {
    public static final String EXCHANGE = "pixflow.vision";
    public static final String QUEUE = "pixflow.vision.q";
    public static final String ROUTING_KEY = "vision.copy_enrich";
    public static final String DLX = "pixflow.vision.dlx";
    public static final String DLQ = "pixflow.vision.dlq";
    public static final String DEAD_ROUTING_KEY = "vision.copy_enrich.dead";
    public static final String RETRY_QUEUE = "pixflow.vision.retry.q";
    public static final String RETRY_ROUTING_KEY = "vision.copy_enrich.retry";

    private CopyEnrichmentTopology() {
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
