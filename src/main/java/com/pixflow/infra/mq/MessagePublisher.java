package com.pixflow.infra.mq;

public interface MessagePublisher {
    PublishResult publish(PublishRequest request);
}
