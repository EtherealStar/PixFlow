package com.pixflow.infra.vector.observability;

import java.time.Duration;

public interface VectorMetrics {
    void recordOperation(String operation, String result, Duration duration);

    void recordSearchReturned(int count);
}
