package com.pixflow.module.vision.execution;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.pixflow.infra.mq.MessageEnvelope;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VisionMqConsumerTest {
    @Test
    void routesAllVisionMessageKindsToNarrowHandlers() throws Exception {
        VisionMessageHandlers handlers = mock(VisionMessageHandlers.class);

        VisionMqConsumer.packages(handlers).handle(MessageEnvelope.current(
                new VisionMqDestination.PackageMessage("event-1", 7L), Map.of()));
        VisionMqConsumer.skus(handlers).handle(MessageEnvelope.current(
                new VisionMqDestination.SkuMessage("event-2", 7L, "SKU-1"), Map.of()));
        VisionMqConsumer.items(handlers).handle(MessageEnvelope.current(
                new VisionMqDestination.ItemMessage(9L), Map.of()));

        verify(handlers).packageRequested(7L);
        verify(handlers).skuInputChanged(7L, "SKU-1");
        verify(handlers).analyzeItem(9L);
    }
}
