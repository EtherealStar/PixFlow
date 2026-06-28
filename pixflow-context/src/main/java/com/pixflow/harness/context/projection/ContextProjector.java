package com.pixflow.harness.context.projection;

import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageRole;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ContextProjector {
    private final int maxMessages;

    public ContextProjector(int maxMessages) {
        this.maxMessages = Math.max(1, maxMessages);
    }

    public List<Message> project(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, messages.size() - maxMessages);
        start = adjustStartIndexToPreserveToolPairs(messages, start);
        List<Message> projected = messages.subList(start, messages.size());
        return dropOrphanToolResults(projected);
    }

    public int adjustStartIndexToPreserveToolPairs(List<Message> messages, int startIndex) {
        int start = Math.max(0, startIndex);
        if (start >= messages.size()) {
            return messages.size();
        }
        Set<String> requiredToolCalls = new HashSet<>();
        for (int i = start; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message.role() == MessageRole.TOOL_RESULT) {
                requiredToolCalls.add(message.toolCallId());
            }
        }
        if (requiredToolCalls.isEmpty()) {
            return start;
        }

        // 如果滑窗切在 tool call/result 中间，向前回退到对应 assistant，避免 provider 看到孤立 tool_result。
        for (int i = start - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.role() == MessageRole.ASSISTANT) {
                for (String toolCallId : message.metadata().toolCallIds()) {
                    if (requiredToolCalls.contains(toolCallId)) {
                        start = i;
                        requiredToolCalls.remove(toolCallId);
                    }
                }
            }
            if (requiredToolCalls.isEmpty()) {
                break;
            }
        }
        return start;
    }

    private List<Message> dropOrphanToolResults(List<Message> messages) {
        Set<String> visibleToolCalls = new HashSet<>();
        java.util.ArrayList<Message> result = new java.util.ArrayList<>(messages.size());
        for (Message message : messages) {
            if (message.role() == MessageRole.ASSISTANT) {
                visibleToolCalls.addAll(message.metadata().toolCallIds());
                result.add(message);
            } else if (message.role() == MessageRole.TOOL_RESULT) {
                if (visibleToolCalls.contains(message.toolCallId())) {
                    result.add(message);
                }
            } else {
                result.add(message);
            }
        }
        return List.copyOf(result);
    }
}
