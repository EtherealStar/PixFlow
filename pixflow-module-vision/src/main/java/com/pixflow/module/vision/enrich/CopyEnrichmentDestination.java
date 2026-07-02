package com.pixflow.module.vision.enrich;

import com.pixflow.infra.mq.destination.ConsumerBinding;
import com.pixflow.infra.mq.destination.MessageDestination;

public final class CopyEnrichmentDestination {
    public static final String TOPIC = "pixflow-vision";
    public static final String TAG = "COPY_ENRICH";
    public static final String CONSUMER_GROUP = "pixflow-vision-enricher";

    private CopyEnrichmentDestination() {}

    public static MessageDestination destination(long packageId) {
        return MessageDestination.of(TOPIC, TAG).withKey("package:" + packageId);
    }

    public static ConsumerBinding binding() {
        return ConsumerBinding.of(TOPIC, TAG, CONSUMER_GROUP, CopyEnrichmentMessage.class);
    }
}
