package com.pixflow.infra.ai.chat;

/**
 * 统一终止原因。
 */
public enum StopReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    CONTENT_FILTER,
    OTHER
}
