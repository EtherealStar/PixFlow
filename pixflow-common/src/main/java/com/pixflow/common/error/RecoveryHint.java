package com.pixflow.common.error;

/**
 * 统一控制流提示，供工具管线、任务 worker 和流式出口消费。
 */
public enum RecoveryHint {
    RETRY,
    SKIP,
    TERMINATE,
    COMPACT
}
