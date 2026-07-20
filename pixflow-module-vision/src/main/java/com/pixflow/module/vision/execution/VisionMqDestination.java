package com.pixflow.module.vision.execution;

import com.pixflow.infra.mq.destination.ConsumerBinding;
import com.pixflow.infra.mq.destination.MessageDestination;

public final class VisionMqDestination {
    public static final String TOPIC = "pixflow-vision";

    public static final String PACKAGE_TAG = "PACKAGE_VISUAL_ANALYSIS_REQUESTED";

    public static final String SKU_TAG = "SKU_VISUAL_INPUT_CHANGED";

    public static final String ITEM_TAG = "VISION_ANALYZE_ITEM";

    private VisionMqDestination() { }

    public static MessageDestination packageDestination() {
        return MessageDestination.of(TOPIC, PACKAGE_TAG);
    }

    public static MessageDestination skuDestination() {
        return MessageDestination.of(TOPIC, SKU_TAG);
    }

    public static MessageDestination itemDestination() {
        return MessageDestination.of(TOPIC, ITEM_TAG);
    }

    public static ConsumerBinding packageBinding() {
        return ConsumerBinding.of(TOPIC, PACKAGE_TAG, "pixflow-vision-package", PackageMessage.class);
    }

    public static ConsumerBinding skuBinding() {
        return ConsumerBinding.of(TOPIC, SKU_TAG, "pixflow-vision-sku", SkuMessage.class);
    }

    public static ConsumerBinding itemBinding() {
        return ConsumerBinding.of(TOPIC, ITEM_TAG, "pixflow-vision-worker", ItemMessage.class);
    }

    public record PackageMessage(String eventId, long packageId) { }

    public record SkuMessage(String eventId, long packageId, String skuId) { }

    public record ItemMessage(long itemId) { }
}
