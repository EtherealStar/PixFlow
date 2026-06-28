package com.pixflow.harness.context.snapshot;

import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.ToolResultReference;
import java.util.List;
import java.util.Map;

public record PreparedContext(
        List<Message> messages,
        Map<String, Object> usageHints,
        List<ToolResultReference> transcriptRefs) {

    public PreparedContext {
        messages = List.copyOf(messages == null ? List.of() : messages);
        usageHints = Map.copyOf(usageHints == null ? Map.of() : usageHints);
        transcriptRefs = List.copyOf(transcriptRefs == null ? List.of() : transcriptRefs);
    }
}
