package com.pixflow.module.file.ingest;

import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.PublishRequest;
import com.pixflow.infra.mq.PublishResult;

public class ExtractionPublisher {
    private final MessagePublisher messagePublisher;

    public ExtractionPublisher(MessagePublisher messagePublisher) {
        this.messagePublisher = messagePublisher;
    }

    public PublishResult publish(long packageId) {
        return messagePublisher.publish(PublishRequest.of(
                ExtractionTopology.EXCHANGE,
                ExtractionTopology.ROUTING_KEY,
                new ExtractionMessage(packageId)));
    }
}
