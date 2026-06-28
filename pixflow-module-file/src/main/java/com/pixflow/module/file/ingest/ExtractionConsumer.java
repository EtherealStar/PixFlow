package com.pixflow.module.file.ingest;

import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.consumer.ManagedMessageHandler;

public class ExtractionConsumer implements ManagedMessageHandler<ExtractionMessage> {
    private final ZipExtractor zipExtractor;

    public ExtractionConsumer(ZipExtractor zipExtractor) {
        this.zipExtractor = zipExtractor;
    }

    @Override
    public void handle(MessageEnvelope<ExtractionMessage> envelope) {
        zipExtractor.extract(envelope.payload().packageId());
    }
}
