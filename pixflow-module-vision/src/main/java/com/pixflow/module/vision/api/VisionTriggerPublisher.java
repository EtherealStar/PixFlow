package com.pixflow.module.vision.api;

/**
 * File/App 触发 Vision 当前输入重算时使用的公开消息边界。
 */
public interface VisionTriggerPublisher {
    void packageReady(String eventId, long packageId);

    void skuInputChanged(String eventId, long packageId, String skuId);
}
