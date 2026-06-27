package com.pixflow.infra.mq.observability;

import com.pixflow.infra.mq.PublishFailureType;

public interface MqMetrics {
    void recordPublishConfirmed(String exchange, String routingKey);

    void recordPublishFailed(String exchange, String routingKey, PublishFailureType failureType);

    void recordConsumeAck(String queue);

    void recordConsumeRetry(String queue, int retryCount);

    void recordConsumeDeadLetter(String queue);

    void recordConsumeAckDrop(String queue);

    void recordDlqDepth(String queue, long depth);
}
