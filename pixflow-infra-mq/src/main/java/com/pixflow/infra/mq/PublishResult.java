package com.pixflow.infra.mq;

import java.util.Objects;

public record PublishResult(
        boolean confirmed,
        PublishFailure failure,
        String topic,
        String tag,
        String messageId,
        String queueInfo) {
    public PublishResult {
        if (confirmed && failure != null) {
            throw new IllegalArgumentException("confirmed result must not carry failure");
        }
        if (!confirmed && failure == null) {
            throw new IllegalArgumentException("failed result must carry failure");
        }
        Objects.requireNonNull(topic, "topic must not be null");
        Objects.requireNonNull(tag, "tag must not be null");
        messageId = messageId == null ? "" : messageId;
        queueInfo = queueInfo == null ? "" : queueInfo;
    }

    public static PublishResult confirmed(String topic, String tag, String messageId, String queueInfo) {
        return new PublishResult(true, null, topic, tag, messageId, queueInfo);
    }

    public static PublishResult failed(String topic, String tag, String messageId, PublishFailure failure) {
        return new PublishResult(false, failure, topic, tag, messageId, "");
    }

    public boolean failed() {
        return !confirmed;
    }
}
