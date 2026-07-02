package com.pixflow.infra.mq;

public enum PublishFailureType {
    BROKER_REJECTED,
    SEND_TIMEOUT,
    SERIALIZATION_FAILED,
    BROKER_UNAVAILABLE,
    INVALID_DESTINATION,
    UNKNOWN
}
