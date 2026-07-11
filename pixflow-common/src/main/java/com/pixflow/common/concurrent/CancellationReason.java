package com.pixflow.common.concurrent;

public enum CancellationReason {
    CLIENT_DISCONNECTED,
    TIMEOUT,
    SERVER_SHUTDOWN,
    CALLER_ABORTED
}
