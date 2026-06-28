package com.pixflow.harness.context.compaction;

import com.pixflow.harness.context.model.Message;
import java.util.List;
import java.util.Map;

public record CompactionResult(
        CompactionTrigger trigger,
        List<Message> messages,
        int tokenBefore,
        int tokenAfter,
        boolean summarized,
        boolean fallback,
        Map<String, Object> metadata) {

    public CompactionResult {
        messages = List.copyOf(messages == null ? List.of() : messages);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }
}
