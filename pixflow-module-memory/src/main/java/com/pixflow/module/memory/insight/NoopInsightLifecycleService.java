package com.pixflow.module.memory.insight;

import com.pixflow.module.memory.lifecycle.MemoryReinforcementEvent;

public class NoopInsightLifecycleService implements InsightLifecycleService {
    @Override
    public void maintain() {
    }

    @Override
    public void suppress(String insightId, String reason) {
    }

    @Override
    public void expire(String insightId, String reason) {
    }

    @Override
    public void reinforce(MemoryReinforcementEvent event) {
    }
}
