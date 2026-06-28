package com.pixflow.harness.context.engine;

import com.pixflow.harness.context.compaction.CompactionResult;
import com.pixflow.harness.context.compaction.ContextCompactionService;
import com.pixflow.harness.context.runtime.CurrentModelContext;
import com.pixflow.harness.context.snapshot.ContextSnapshot;
import com.pixflow.harness.context.snapshot.PreparedContext;
import com.pixflow.harness.context.snapshot.ToolSchemaView;
import com.pixflow.harness.context.store.MessageStore;
import java.util.List;

public final class ContextEngine {
    private final MessageStore messageStore;
    private final ContextCompactionService compactionService;
    private final CurrentModelContext currentModelContext;

    public ContextEngine(MessageStore messageStore, ContextCompactionService compactionService, CurrentModelContext currentModelContext) {
        this.messageStore = messageStore;
        this.compactionService = compactionService;
        this.currentModelContext = currentModelContext;
    }

    public ContextSnapshot buildForModel(String systemPrompt, List<ToolSchemaView> toolSchemas) {
        PreparedContext prepared = compactionService.prepare(messageStore.currentMessages());
        CompactionResult transition = compactionService.maybeAutoCompact(messageStore, prepared).orElse(null);
        if (transition != null) {
            prepared = compactionService.prepare(messageStore.currentMessages());
        }
        ContextSnapshot snapshot = new ContextSnapshot(
                systemPrompt,
                prepared.messages(),
                toolSchemas,
                prepared.usageHints(),
                prepared.transcriptRefs(),
                transition);
        currentModelContext.set(snapshot);
        return snapshot;
    }
}
