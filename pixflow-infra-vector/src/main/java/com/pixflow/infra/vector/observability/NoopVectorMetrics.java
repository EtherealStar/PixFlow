package com.pixflow.infra.vector.observability;

import java.time.Duration;

public class NoopVectorMetrics implements VectorMetrics {
    @Override
    public void recordOperation(String operation, String result, Duration duration) {
    }

    @Override
    public void recordSearchReturned(int count) {
    }
}
