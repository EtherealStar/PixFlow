package com.pixflow.infra.mq.trace;

import java.util.Map;

public interface TraceHeaderPropagator {
    Map<String, Object> inject(Map<String, Object> headers);

    TraceScope restore(Map<String, Object> headers);
}
