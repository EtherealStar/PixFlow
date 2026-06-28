package com.pixflow.harness.context.compaction;

import com.pixflow.harness.context.model.Message;
import java.util.List;
import java.util.Map;

public interface SummarizationPort {
    SummaryResult summarize(SummarizationRequest request);

    record SummarizationRequest(
            List<Message> messages,
            CompactionTrigger trigger,
            String focus,
            List<String> summaryInstructions,
            Map<String, Object> metadata) {
        public SummarizationRequest {
            messages = List.copyOf(messages == null ? List.of() : messages);
            summaryInstructions = List.copyOf(summaryInstructions == null ? List.of() : summaryInstructions);
            metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        }
    }

    record SummaryResult(String summary) {
        public SummaryResult {
            summary = summary == null ? "" : summary;
        }
    }
}
