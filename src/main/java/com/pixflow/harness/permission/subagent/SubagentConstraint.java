package com.pixflow.harness.permission.subagent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 子 Agent 的硬约束说明。
 */
public record SubagentConstraint(
        String agentType,
        boolean readOnly,
        Set<String> allowedTools,
        Set<String> disallowedTools) {

    public SubagentConstraint {
        agentType = normalize(agentType);
        allowedTools = immutableSet(allowedTools);
        disallowedTools = immutableSet(disallowedTools);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("agentType 不能为空");
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
