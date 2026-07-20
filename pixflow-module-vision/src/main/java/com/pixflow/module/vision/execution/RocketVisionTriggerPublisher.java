package com.pixflow.module.vision.execution;

import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.PublishRequest;
import com.pixflow.infra.mq.PublishResult;
import com.pixflow.module.vision.api.VisionTriggerPublisher;
import java.util.Map;
import java.util.Objects;

/** Vision owner 内部的触发消息发布器；topic/tag 不穿过公开 API。 */
public final class RocketVisionTriggerPublisher implements VisionTriggerPublisher {
    private final MessagePublisher publisher;

    public RocketVisionTriggerPublisher(MessagePublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    @Override
    public void packageReady(String eventId, long packageId) {
        publish(eventId, VisionMqDestination.PACKAGE_TAG,
                Map.of("eventId", eventId, "packageId", packageId));
    }

    @Override
    public void skuInputChanged(String eventId, long packageId, String skuId) {
        publish(eventId, VisionMqDestination.SKU_TAG,
                Map.of("eventId", eventId, "packageId", packageId, "skuId", skuId));
    }

    private void publish(String eventId, String tag, Object payload) {
        PublishResult result = publisher.publish(
                PublishRequest.of(VisionMqDestination.TOPIC, tag, payload).withKey(eventId));
        if (!result.confirmed()) {
            throw new IllegalStateException("vision trigger was not confirmed");
        }
    }
}
