package com.pixflow.infra.mq.observability;

import com.pixflow.infra.mq.PublishFailureType;

public interface MqMetrics {
    void recordPublishConfirmed(String topic, String tag);
    void recordPublishFailed(String topic, String tag, PublishFailureType failureType);
    void recordConsumeAck(String topic, String consumerGroup);
    void recordConsumeRetry(String topic, String consumerGroup, int retryCount);
    void recordConsumeDeadLetter(String topic, String consumerGroup);
    void recordConsumeAckDrop(String topic, String consumerGroup);
    void recordDlqDepth(String topic, String consumerGroup, long depth);
}
