package com.pixflow.harness.context.store;

import com.pixflow.harness.context.compaction.CompactionTrigger;
import com.pixflow.harness.context.model.Message;
import java.util.List;
import java.util.Map;

public interface TranscriptPort {
    List<Message> append(String conversationId, List<Message> messages);

    List<Message> load(String conversationId);

    List<Message> replaceForCompaction(
            String conversationId,
            List<Message> messages,
            CompactionTrigger trigger,
            Map<String, Object> metadata);
}
