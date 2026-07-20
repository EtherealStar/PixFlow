package com.pixflow.module.memory.recall;

import com.pixflow.module.memory.context.MemoryReference;
import java.util.List;

public interface RecallReferenceResolver {
    ResolvedRecallReferences resolve(List<MemoryReference> references);
}
