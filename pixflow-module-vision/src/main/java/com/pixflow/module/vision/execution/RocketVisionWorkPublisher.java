package com.pixflow.module.vision.execution;

import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.PublishRequest;
import com.pixflow.infra.mq.PublishResult;
import java.util.Map;

public final class RocketVisionWorkPublisher implements VisionWorkPublisher {
    private final MessagePublisher publisher;

    public RocketVisionWorkPublisher(MessagePublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(long itemId) {
        PublishResult result = publisher.publish(
                PublishRequest.of(VisionMqDestination.TOPIC, VisionMqDestination.ITEM_TAG,
                        Map.of("itemId", itemId)).withKey("vision-item:" + itemId));
        if (!result.confirmed()) {
            throw new IllegalStateException("vision work publish was not confirmed");
        }
    }
}
