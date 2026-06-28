package com.pixflow.harness.context.budget;

import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.model.MessageRole;
import com.pixflow.harness.context.model.ToolResultReference;
import com.pixflow.harness.context.projection.ContextProjector;
import com.pixflow.harness.context.snapshot.PreparedContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ContextBudgetService {
    private final ContextBudgetConfig config;
    private final TokenEstimator tokenEstimator;
    private final ToolResultExternalizer externalizer;

    public ContextBudgetService(ContextBudgetConfig config, TokenEstimator tokenEstimator, ToolResultExternalizer externalizer) {
        this.config = config == null ? ContextBudgetConfig.defaults() : config;
        this.tokenEstimator = tokenEstimator == null ? new ConservativeTokenEstimator() : tokenEstimator;
        this.externalizer = externalizer;
    }

    public PreparedContext prepare(List<Message> messages) {
        List<ToolResultReference> refs = new ArrayList<>();
        List<Message> budgeted = externalizeLargeToolResults(messages, refs);
        List<Message> projected = new ContextProjector(config.snipMaxMessages()).project(budgeted);
        List<Message> compacted = microcompact(projected);
        int tokenAfter = tokenEstimator.estimate(compacted);
        Map<String, Object> usageHints = new LinkedHashMap<>();
        usageHints.put("tokenAfter", tokenAfter);
        usageHints.put("messageCount", compacted.size());
        usageHints.put("externalizedToolResultCount", refs.size());
        return new PreparedContext(compacted, usageHints, refs);
    }

    private List<Message> externalizeLargeToolResults(List<Message> messages, List<ToolResultReference> refs) {
        List<Message> result = new ArrayList<>();
        for (Message message : messages == null ? List.<Message>of() : messages) {
            if (message.role() == MessageRole.TOOL_RESULT
                    && !message.metadata().flag(MessageMetadata.TOOL_RESULT_EXTERNALIZED)
                    && message.content().getBytes(StandardCharsets.UTF_8).length > config.toolResultExternalizeThresholdBytes()
                    && externalizer != null) {
                ToolResultReference ref = externalizer.externalize(message.toolCallId(), message.content(), config.previewChars());
                refs.add(ref);
                String visible = "[tool result externalized: " + ref.bucket() + "/" + ref.key() + "]\npreview:\n" + ref.preview();
                MessageMetadata metadata = message.metadata()
                        .with(MessageMetadata.TOOL_RESULT_EXTERNALIZED, true)
                        .with(MessageMetadata.TOOL_RESULT_REF, Map.of(
                                "id", ref.id(),
                                "bucket", ref.bucket(),
                                "key", ref.key(),
                                "originalBytes", ref.originalBytes()));
                result.add(message.withContent(visible).withMetadata(metadata));
            } else {
                result.add(message);
            }
        }
        return List.copyOf(result);
    }

    private List<Message> microcompact(List<Message> messages) {
        int remainingRecentToolResults = config.microcompactKeepRecentToolResults();
        List<Message> reversed = new ArrayList<>(messages);
        java.util.Collections.reverse(reversed);
        List<Message> compactedReversed = new ArrayList<>(reversed.size());
        for (Message message : reversed) {
            if (message.role() == MessageRole.TOOL_RESULT && !message.metadata().flag(MessageMetadata.TOOL_RESULT_EXTERNALIZED)) {
                if (remainingRecentToolResults > 0) {
                    remainingRecentToolResults--;
                    compactedReversed.add(message);
                } else {
                    // 只降级模型可见投影，不改写 MessageStore 里的活动链。
                    MessageMetadata metadata = message.metadata().with(MessageMetadata.MICROCOMPACTED, true);
                    compactedReversed.add(message.withContent("[old tool result omitted by microcompact]").withMetadata(metadata));
                }
            } else {
                compactedReversed.add(message);
            }
        }
        java.util.Collections.reverse(compactedReversed);
        return List.copyOf(compactedReversed);
    }
}
