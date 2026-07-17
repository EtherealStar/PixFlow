package com.pixflow.harness.context.compaction;

import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.context.budget.ContextBudgetService;
import com.pixflow.harness.context.budget.TokenEstimator;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.model.MessageRole;
import com.pixflow.harness.context.projection.ContextProjector;
import com.pixflow.harness.context.snapshot.PreparedContext;
import com.pixflow.harness.context.store.MessageStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ContextCompactionService {
    private final ContextBudgetService budgetService;

    private final TokenEstimator tokenEstimator;

    private final SummarizationPort summarizationPort;

    private final CompactionConfig config;

    private int consecutiveFailures;

    private int reactiveRetries;

    public ContextCompactionService(
            ContextBudgetService budgetService,
            TokenEstimator tokenEstimator,
            SummarizationPort summarizationPort,
            CompactionConfig config) {
        this.budgetService = budgetService;
        this.tokenEstimator = tokenEstimator;
        this.summarizationPort = summarizationPort;
        this.config = config == null ? CompactionConfig.defaults() : config;
    }

    public PreparedContext prepare(List<Message> messages) {
        return budgetService.prepare(messages);
    }

    public Optional<CompactionResult> maybeAutoCompact(MessageStore store, PreparedContext prepared) {
        int tokenAfter = (int) prepared.usageHints().getOrDefault("tokenAfter", 0);
        if (tokenAfter < config.autoCompactThreshold()) {
            return Optional.empty();
        }
        return Optional.of(compact(store, CompactionTrigger.AUTO, null, Map.of()));
    }

    public CompactionResult manualCompact(MessageStore store, String focus, Map<String, Object> metadata) {
        return compact(store, CompactionTrigger.MANUAL, focus, metadata);
    }

    public CompactionResult reactiveCompact(
            MessageStore store,
            PixFlowException contextLimitError,
            Map<String, Object> metadata) {
        if (reactiveRetries >= config.maxReactiveRetries()) {
            Map<String, Object> merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
            merged.put("reactiveRetryExceeded", true);
            return fallback(store, CompactionTrigger.REACTIVE, merged);
        }
        reactiveRetries++;
        Map<String, Object> merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        if (contextLimitError != null) {
            merged.put("contextLimitCode", contextLimitError.code().code());
        }
        return compact(store, CompactionTrigger.REACTIVE, null, merged);
    }

    private CompactionResult compact(
            MessageStore store,
            CompactionTrigger trigger,
            String focus,
            Map<String, Object> metadata) {
        List<Message> original = store.currentMessages();
        int before = tokenEstimator.estimate(original);
        if (summarizationPort == null || consecutiveFailures >= config.maxConsecutiveFailures()) {
            return fallback(store, trigger, metadata);
        }
        try {
            List<Message> tail = tail(original);
            List<Message> head = original.subList(0, Math.max(0, original.size() - tail.size()));
            List<String> instructions = summaryInstructions(metadata);
            SummarizationPort.SummaryResult summary = summarizationPort.summarize(
                    new SummarizationPort.SummarizationRequest(
                            head, trigger, focus, instructions, metadata));
            List<Message> replacement = buildSummarizedMessages(summary.summary(), tail, trigger);
            store.replaceMessagesForCompaction(replacement, trigger, metadata);
            consecutiveFailures = 0;
            int after = tokenEstimator.estimate(replacement);
            return new CompactionResult(trigger, replacement, before, after, true, false, metadata);
        } catch (RuntimeException ex) {
            consecutiveFailures++;
            Map<String, Object> merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
            merged.put("compactFailed", ex.getClass().getSimpleName());
            return fallback(store, trigger, merged);
        }
    }

    private CompactionResult fallback(MessageStore store, CompactionTrigger trigger, Map<String, Object> metadata) {
        List<Message> original = store.currentMessages();
        int before = tokenEstimator.estimate(original);
        List<Message> tail = tail(original);
        Message boundary = boundary(trigger);
        List<Message> replacement = new ArrayList<>();
        replacement.add(boundary);
        replacement.addAll(tail);
        store.replaceMessagesForCompaction(replacement, trigger, metadata);
        int after = tokenEstimator.estimate(replacement);
        return new CompactionResult(trigger, replacement, before, after, false, true, metadata);
    }

    private List<Message> buildSummarizedMessages(String summary, List<Message> tail, CompactionTrigger trigger) {
        List<Message> replacement = new ArrayList<>();
        replacement.add(boundary(trigger));
        MessageMetadata summaryMetadata = MessageMetadata.empty()
                .with(MessageMetadata.COMPACT_SUMMARY, true)
                .with(MessageMetadata.COMPACT_TRIGGER, trigger.name());
        replacement.add(new Message(
                null,
                MessageRole.USER,
                "Conversation summary:\n" + summary,
                null,
                summaryMetadata,
                Instant.now()));
        replacement.addAll(tail);
        return List.copyOf(replacement);
    }

    private Message boundary(CompactionTrigger trigger) {
        MessageMetadata metadata = MessageMetadata.empty()
                .with(MessageMetadata.COMPACT_BOUNDARY, true)
                .with(MessageMetadata.COMPACT_TRIGGER, trigger.name());
        return new Message(
                null,
                MessageRole.USER,
                "[context compacted before this point]",
                null,
                metadata,
                Instant.now());
    }

    private List<Message> tail(List<Message> messages) {
        // tail 同样走投影器，确保压缩后不会留下孤立 tool_result。
        return new ContextProjector(config.tailMaxMessages()).project(messages);
    }

    private static List<String> summaryInstructions(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("compact.summaryInstructions");
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    public PixFlowException contextLimit(String message) {
        return new PixFlowException(CommonErrorCode.CONTEXT_LIMIT_EXCEEDED, message);
    }
}
