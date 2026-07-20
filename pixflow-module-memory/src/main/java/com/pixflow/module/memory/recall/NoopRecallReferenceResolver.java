package com.pixflow.module.memory.recall;

import com.pixflow.module.memory.context.MemoryReference;
import java.util.List;

public final class NoopRecallReferenceResolver implements RecallReferenceResolver {
    @Override
    public ResolvedRecallReferences resolve(List<MemoryReference> references) {
        return new ResolvedRecallReferences(List.of(), List.of(), List.of());
    }
}
