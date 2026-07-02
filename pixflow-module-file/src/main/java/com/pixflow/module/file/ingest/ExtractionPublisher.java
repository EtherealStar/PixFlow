package com.pixflow.module.file.ingest;

import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.PublishRequest;
import com.pixflow.infra.mq.PublishResult;
import com.pixflow.infra.mq.destination.MessageDestination;

public class ExtractionPublisher {
    private final MessagePublisher messagePublisher;

    public ExtractionPublisher(MessagePublisher messagePublisher) {
        this.messagePublisher = messagePublisher;
    }

    public PublishResult publish(long packageId) {
        MessageDestination destination = ExtractionDestination.destination(packageId);
        return messagePublisher.publish(PublishRequest
                .of(destination.topic(), destination.tag(), new ExtractionMessage(packageId))
                .withKeys(destination.keys()));
    }
}
