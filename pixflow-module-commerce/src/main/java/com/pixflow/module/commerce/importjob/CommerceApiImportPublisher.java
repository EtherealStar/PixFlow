package com.pixflow.module.commerce.importjob;

import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.PublishRequest;
import com.pixflow.infra.mq.PublishResult;
import com.pixflow.infra.mq.destination.MessageDestination;

public class CommerceApiImportPublisher {
    private final MessagePublisher publisher;

    public CommerceApiImportPublisher(MessagePublisher publisher) {
        this.publisher = publisher;
    }

    public PublishResult publish(long jobId) {
        MessageDestination destination = CommerceImportDestination.destination(jobId);
        return publisher.publish(PublishRequest
                .of(destination.topic(), destination.tag(), new CommerceApiImportMessage(jobId))
                .withKeys(destination.keys()));
    }
}
