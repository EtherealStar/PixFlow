package com.pixflow.infra.mq.consumer;

public interface ManagedMessageContainer {
    void start();

    void stop();

    boolean isRunning();
}
