package com.pixflow.infra.mq.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

public enum MqErrorCode implements ErrorCode {
    MQ_PUBLISH_RETURNED,
    MQ_PUBLISH_NACKED,
    MQ_CONFIRM_TIMEOUT,
    MQ_BROKER_UNAVAILABLE,
    MQ_TOPOLOGY_DECLARE_FAILED,
    MQ_MESSAGE_SERIALIZATION_FAILED,
    MQ_MESSAGE_DESERIALIZATION_FAILED,
    MQ_MESSAGE_SCHEMA_UNSUPPORTED,
    MQ_RETRY_FORWARD_FAILED,
    MQ_DLQ_FORWARD_FAILED;

    @Override
    public String code() {
        return name();
    }

    @Override
    public ErrorCategory category() {
        return ErrorCategory.DEPENDENCY;
    }
}
