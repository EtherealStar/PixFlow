package com.pixflow.harness.state.observability;

import com.pixflow.harness.state.model.ProgressSource;

public interface StateMetrics {
    void recordSnapshot(String result, long nanos);

    void recordProgressSource(ProgressSource source);

    void recordProgressDrift(long drift);

    void recordSkippableUnits(int count);
}
