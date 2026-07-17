package com.pixflow.module.memory;

import com.pixflow.module.memory.context.MemoryContext;
import com.pixflow.module.memory.context.MemoryContextRequest;

public interface MemoryService {
    MemoryContext prepareContext(MemoryContextRequest request);
}
