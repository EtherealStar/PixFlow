package com.pixflow.app.web.conversation.sse;

public enum SseTerminationReason {
    COMPLETED,
    BUSINESS_ERROR,
    USER_STOPPED,
    CLIENT_DISCONNECTED,
    TIMEOUT,
    SERVER_SHUTDOWN,
    CAPACITY_REJECTED
}
