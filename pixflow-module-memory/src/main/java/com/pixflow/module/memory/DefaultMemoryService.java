package com.pixflow.module.memory;

import com.pixflow.module.memory.context.MemoryContext;
import com.pixflow.module.memory.context.MemoryContextBuilder;
import com.pixflow.module.memory.context.MemoryContextRequest;
import java.util.Objects;

public class DefaultMemoryService implements MemoryService {
    private final MemoryContextBuilder contextBuilder;

    public DefaultMemoryService(MemoryContextBuilder contextBuilder) {
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
    }

    @Override
    public MemoryContext prepareContext(MemoryContextRequest request) {
        return contextBuilder.build(request);
    }
}
