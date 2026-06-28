package com.pixflow.module.memory.lifecycle;

public class NoopMemoryReinforcementService implements MemoryReinforcementService {
    @Override
    public void reinforce(MemoryReinforcementEvent event) {
        // 后续由 InsightLifecycleService 更新 access_count、importance 与 decay_score。
    }
}
