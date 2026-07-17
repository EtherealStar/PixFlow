package com.pixflow.module.commerce.importjob;

import com.pixflow.infra.mq.destination.ConsumerBinding;
import com.pixflow.infra.mq.destination.MessageDestination;

public final class CommerceImportDestination {
    public static final String TOPIC = "pixflow-commerce";

    public static final String TAG = "COMMERCE_IMPORT";

    public static final String CONSUMER_GROUP = "pixflow-commerce-importer";

    private CommerceImportDestination() { }

    public static MessageDestination destination(long jobId) {
        return MessageDestination.of(TOPIC, TAG).withKey("import:" + jobId);
    }

    public static ConsumerBinding binding() {
        return ConsumerBinding.of(TOPIC, TAG, CONSUMER_GROUP, CommerceApiImportMessage.class);
    }
}
