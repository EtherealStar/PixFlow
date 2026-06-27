package com.pixflow.infra.ai.model;

/**
 * 逻辑能力轴，用于区分同一套路由下的不同职责。
 */
public enum ModelCapability {
    CHAT,
    VISION,
    IMAGEGEN,
    EMBEDDING,
    RERANK
}
