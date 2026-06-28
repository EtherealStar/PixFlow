package com.pixflow.harness.context.compaction;

import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.context.budget.ConservativeTokenEstimator;
import com.pixflow.harness.context.budget.ContextBudgetConfig;
import com.pixflow.harness.context.budget.ContextBudgetService;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.store.MessageStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextCompactionServiceTest {
    @Test
    void manualCompactCreatesBoundarySummaryAndTail() {
        MessageStore store = new MessageStore();
        store.appendUser("old");
        store.appendAssistant(Message.assistant("assistant old"));
        store.appendUser("latest");
        ContextCompactionService service = service(request -> new SummarizationPort.SummaryResult("summary text"));

        CompactionResult result = service.manualCompact(store, "focus", Map.of("compact.summaryInstructions", "keep sku"));

        assertThat(result.summarized()).isTrue();
        assertThat(store.currentMessages().get(0).metadata().flag(MessageMetadata.COMPACT_BOUNDARY)).isTrue();
        assertThat(store.currentMessages()).anySatisfy(message ->
                assertThat(message.metadata().flag(MessageMetadata.COMPACT_SUMMARY)).isTrue());
    }

    @Test
    void fallsBackWhenSummarizerMissing() {
        MessageStore store = new MessageStore();
        store.appendUser("old");
        store.appendUser("latest");
        ContextCompactionService service = service(null);

        CompactionResult result = service.reactiveCompact(
                store,
                new PixFlowException(CommonErrorCode.CONTEXT_LIMIT_EXCEEDED, "too large"),
                Map.of());

        assertThat(result.fallback()).isTrue();
        assertThat(store.currentMessages().get(0).metadata().flag(MessageMetadata.COMPACT_BOUNDARY)).isTrue();
    }

    private static ContextCompactionService service(SummarizationPort summarizer) {
        ConservativeTokenEstimator estimator = new ConservativeTokenEstimator();
        return new ContextCompactionService(
                new ContextBudgetService(ContextBudgetConfig.defaults(), estimator, null),
                estimator,
                summarizer,
                new CompactionConfig(100, 10, 10, 2, 2, 1));
    }
}
