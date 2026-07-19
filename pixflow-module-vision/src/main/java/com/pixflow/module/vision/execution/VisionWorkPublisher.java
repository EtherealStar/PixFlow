package com.pixflow.module.vision.execution;

@FunctionalInterface
public interface VisionWorkPublisher {
    void publish(long itemId);
}
