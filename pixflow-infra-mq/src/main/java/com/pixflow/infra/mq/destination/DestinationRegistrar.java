package com.pixflow.infra.mq.destination;

public interface DestinationRegistrar {
    void register(MessageDestination destination);

    void register(ConsumerBinding binding);
}
