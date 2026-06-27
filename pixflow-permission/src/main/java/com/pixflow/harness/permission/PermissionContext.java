package com.pixflow.harness.permission;

import com.pixflow.contracts.confirmation.ConfirmationToken;
import com.pixflow.harness.permission.subagent.SubagentConstraint;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 服务端可信上下文。
 */
public record PermissionContext(
        String conversationId,
        ConfirmationToken pendingToken,
        SubagentConstraint subagent,
        Set<String> deniedTools,
        Set<String> disabledTools) {

    public PermissionContext {
        conversationId = normalizeConversationId(conversationId);
        deniedTools = immutableSet(deniedTools);
        disabledTools = immutableSet(disabledTools);
    }

    public boolean isSubagent() {
        return subagent != null;
    }

    private static String normalizeConversationId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        return value;
    }

    private static Set<String> immutableSet(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
