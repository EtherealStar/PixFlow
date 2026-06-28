package com.pixflow.module.memory;

import com.pixflow.module.memory.context.MemoryContext;
import com.pixflow.module.memory.context.MemoryContextRequest;
import com.pixflow.module.memory.ingest.MemoryIngestRequest;
import com.pixflow.module.memory.lifecycle.MemoryReinforcementEvent;

public interface MemoryService {
    MemoryContext prepareContext(MemoryContextRequest request);

    void ingestAsync(MemoryIngestRequest request);

    void reinforce(MemoryReinforcementEvent event);
}
