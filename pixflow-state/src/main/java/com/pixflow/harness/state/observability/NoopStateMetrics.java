package com.pixflow.harness.state.observability;

import com.pixflow.harness.state.model.ProgressSource;

public class NoopStateMetrics implements StateMetrics {
    @Override
    public void recordSnapshot(String result, long nanos) {
    }

    @Override
    public void recordProgressSource(ProgressSource source) {
    }

    @Override
    public void recordProgressDrift(long drift) {
    }

    @Override
    public void recordSkippableUnits(int count) {
    }
}
