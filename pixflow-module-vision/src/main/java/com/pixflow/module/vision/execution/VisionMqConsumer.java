package com.pixflow.module.vision.execution;

import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.consumer.ManagedMessageHandler;
import java.util.Objects;

public final class VisionMqConsumer {
    private VisionMqConsumer() { }

    public static ManagedMessageHandler<VisionMqDestination.PackageMessage> packages(
            VisionMessageHandlers handlers) {
        Objects.requireNonNull(handlers, "handlers");
        return envelope -> handlers.packageRequested(require(envelope).packageId());
    }

    public static ManagedMessageHandler<VisionMqDestination.SkuMessage> skus(
            VisionMessageHandlers handlers) {
        Objects.requireNonNull(handlers, "handlers");
        return envelope -> {
            VisionMqDestination.SkuMessage message = require(envelope);
            handlers.skuInputChanged(message.packageId(), message.skuId());
        };
    }

    public static ManagedMessageHandler<VisionMqDestination.ItemMessage> items(
            VisionMessageHandlers handlers) {
        Objects.requireNonNull(handlers, "handlers");
        return envelope -> handlers.analyzeItem(require(envelope).itemId());
    }

    private static <T> T require(MessageEnvelope<T> envelope) {
        if (!envelope.supportedVersion() || envelope.payload() == null) {
            throw new IllegalArgumentException("unsupported or empty vision message");
        }
        return envelope.payload();
    }
}
