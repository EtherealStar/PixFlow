package com.pixflow.module.file.ingest;

import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.consumer.ManagedMessageHandler;

public class ExtractionConsumer implements ManagedMessageHandler<ExtractionMessage> {
    private final ArchiveExtractionDispatcher dispatcher;

    public ExtractionConsumer(ArchiveExtractionDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void handle(MessageEnvelope<ExtractionMessage> envelope) {
        dispatcher.extract(envelope.payload().packageId());
    }
}
