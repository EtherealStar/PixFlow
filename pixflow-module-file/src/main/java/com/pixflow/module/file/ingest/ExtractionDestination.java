package com.pixflow.module.file.ingest;

import com.pixflow.infra.mq.destination.ConsumerBinding;
import com.pixflow.infra.mq.destination.MessageDestination;

public final class ExtractionDestination {
    public static final String TOPIC = "pixflow-file";
    public static final String TAG = "PACKAGE_EXTRACT";
    public static final String CONSUMER_GROUP = "pixflow-file-extractor";

    private ExtractionDestination() {}

    public static MessageDestination destination(long packageId) {
        return MessageDestination.of(TOPIC, TAG).withKey("package:" + packageId);
    }

    public static ConsumerBinding binding() {
        return ConsumerBinding.of(TOPIC, TAG, CONSUMER_GROUP, ExtractionMessage.class);
    }
}
