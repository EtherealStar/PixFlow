package com.pixflow.harness.context.snapshot;

import com.pixflow.harness.context.compaction.CompactionResult;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.ToolResultReference;
import java.util.List;
import java.util.Map;

public record ContextSnapshot(
        String systemPrompt,
        List<Message> messages,
        List<ToolSchemaView> toolSchemas,
        Map<String, Object> usageHints,
        List<ToolResultReference> transcriptRefs,
        CompactionResult transition) {

    public ContextSnapshot {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        messages = List.copyOf(messages == null ? List.of() : messages);
        toolSchemas = List.copyOf(toolSchemas == null ? List.of() : toolSchemas);
        usageHints = Map.copyOf(usageHints == null ? Map.of() : usageHints);
        transcriptRefs = List.copyOf(transcriptRefs == null ? List.of() : transcriptRefs);
    }
}
