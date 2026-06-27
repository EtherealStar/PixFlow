package com.pixflow.infra.mq;

public enum PublishFailureType {
    RETURNED,
    NACKED,
    CONFIRM_TIMEOUT,
    SERIALIZATION_FAILED,
    BROKER_UNAVAILABLE,
    UNKNOWN
}
