package com.pixflow.module.file.api.visual;

@FunctionalInterface
public interface AssetVisualInputEventSink {
    void publish(AssetVisualInputEvent event);
}
