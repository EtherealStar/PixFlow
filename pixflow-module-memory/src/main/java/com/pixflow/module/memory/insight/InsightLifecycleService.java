package com.pixflow.module.memory.insight;

import com.pixflow.module.memory.lifecycle.MemoryReinforcementService;

public interface InsightLifecycleService extends MemoryReinforcementService {
    void maintain();

    void suppress(String insightId, String reason);

    void expire(String insightId, String reason);
}
