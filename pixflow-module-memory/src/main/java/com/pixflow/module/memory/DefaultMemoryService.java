package com.pixflow.module.memory;

import com.pixflow.module.memory.context.MemoryContext;
import com.pixflow.module.memory.context.MemoryContextBuilder;
import com.pixflow.module.memory.context.MemoryContextRequest;
import com.pixflow.module.memory.ingest.MemoryIngestRequest;
import com.pixflow.module.memory.ingest.MemoryIngestService;
import com.pixflow.module.memory.lifecycle.MemoryReinforcementEvent;
import com.pixflow.module.memory.lifecycle.MemoryReinforcementService;
import java.util.Objects;

public class DefaultMemoryService implements MemoryService {
    private final MemoryContextBuilder contextBuilder;
    private final MemoryIngestService ingestService;
    private final MemoryReinforcementService reinforcementService;

    public DefaultMemoryService(
            MemoryContextBuilder contextBuilder,
            MemoryIngestService ingestService,
            MemoryReinforcementService reinforcementService) {
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
        this.ingestService = Objects.requireNonNull(ingestService, "ingestService");
        this.reinforcementService = Objects.requireNonNull(reinforcementService, "reinforcementService");
    }

    @Override
    public MemoryContext prepareContext(MemoryContextRequest request) {
        return contextBuilder.build(request);
    }

    @Override
    public void ingestAsync(MemoryIngestRequest request) {
        ingestService.ingestAsync(request);
    }

    @Override
    public void reinforce(MemoryReinforcementEvent event) {
        reinforcementService.reinforce(event);
    }
}
