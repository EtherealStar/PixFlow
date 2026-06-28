package com.pixflow.module.commerce.importjob;

import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.consumer.ManagedMessageHandler;

public class CommerceApiImportConsumer implements ManagedMessageHandler<CommerceApiImportMessage> {
    private final CommerceImportJobService jobService;

    public CommerceApiImportConsumer(CommerceImportJobService jobService) {
        this.jobService = jobService;
    }

    @Override
    public void handle(MessageEnvelope<CommerceApiImportMessage> envelope) {
        jobService.run(envelope.payload().jobId());
    }
}
