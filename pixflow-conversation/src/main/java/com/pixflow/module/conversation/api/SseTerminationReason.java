package com.pixflow.module.conversation.api;

public enum SseTerminationReason {
    COMPLETED,
    BUSINESS_ERROR,
    CLIENT_DISCONNECTED,
    TIMEOUT,
    SERVER_SHUTDOWN,
    CAPACITY_REJECTED
}
