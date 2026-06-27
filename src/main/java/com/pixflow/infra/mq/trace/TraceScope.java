package com.pixflow.infra.mq.trace;

public interface TraceScope extends AutoCloseable {
    String traceId();

    @Override
    void close();
}
