package com.pixflow.harness.eval.support;

import com.pixflow.harness.eval.model.TraceExternalPayloadRef;

public interface TraceExternalPayloadStorage {
    TraceExternalPayloadRef put(String payload);

    String get(TraceExternalPayloadRef ref);

    void delete(TraceExternalPayloadRef ref);
}
