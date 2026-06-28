package com.pixflow.module.commerce.importjob;

import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.PublishRequest;
import com.pixflow.infra.mq.PublishResult;

public class CommerceApiImportPublisher {
    private final MessagePublisher publisher;

    public CommerceApiImportPublisher(MessagePublisher publisher) {
        this.publisher = publisher;
    }

    public PublishResult publish(long jobId) {
        return publisher.publish(PublishRequest.of(
                CommerceImportTopology.EXCHANGE,
                CommerceImportTopology.ROUTING_KEY,
                new CommerceApiImportMessage(jobId)));
    }
}
