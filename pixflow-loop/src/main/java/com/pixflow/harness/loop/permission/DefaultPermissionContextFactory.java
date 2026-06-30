package com.pixflow.harness.loop.permission;

import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.harness.permission.PermissionContext;
import com.pixflow.harness.permission.subagent.SubagentConstraint;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link PermissionContextFactory} 默认实现：从 {@link RuntimeState#metadata()}
 * 的子集中取出 {@code deniedTools} / {@code disabledTools} / {@code subagent}。
 *
 * <p>{@code subagent} 字段是 {@code Map<String,Object>}，至少含 {@code agentType}，可选：
 * <ul>
 *   <li>{@code readOnly}：boolean；</li>
 *   <li>{@code allowedTools}：{@code Collection<String>}；</li>
 *   <li>{@code disallowedTools}：{@code Collection<String>}。</li>
 * </ul>
 */
public final class DefaultPermissionContextFactory implements PermissionContextFactory {

    public DefaultPermissionContextFactory() {
    }

    @Override
    public PermissionContext create(RuntimeState state) {
        String conversationId = state == null ? null : state.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalStateException("RuntimeState.conversationId must be set before creating PermissionContext");
        }
        Set<String> denied = toStringSet(state.metadata().get("deniedTools"));
        Set<String> disabled = toStringSet(state.metadata().get("disabledTools"));
        SubagentConstraint subagent = toSubagent(state.metadata().get("subagent"));
        return new PermissionContext(conversationId, null, subagent, denied, disabled);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> toStringSet(Object value) {
        if (value == null) {
            return Set.of();
        }
        if (value instanceof Set<?> set) {
            return set.stream().filter(java.util.Objects::nonNull)
                    .map(String::valueOf).collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (value instanceof java.util.Collection<?> coll) {
            return coll.stream().filter(java.util.Objects::nonNull)
                    .map(String::valueOf).collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (value instanceof String text && !text.isBlank()) {
            return new LinkedHashSet<>(java.util.Arrays.asList(text.split(",")));
        }
        return Set.of();
    }

    @SuppressWarnings("unchecked")
    private static SubagentConstraint toSubagent(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Object agentTypeObj = map.get("agentType");
        if (agentTypeObj == null || String.valueOf(agentTypeObj).isBlank()) {
            return null;
        }
        String agentType = String.valueOf(agentTypeObj);
        boolean readOnly = Boolean.TRUE.equals(map.get("readOnly"));
        Set<String> allowed = toStringSet(map.get("allowedTools"));
        Set<String> disallowed = toStringSet(map.get("disallowedTools"));
        return new SubagentConstraint(agentType, readOnly, allowed, disallowed);
    }

    /** 仅暴露给测试 —— 把 set 转成不可变视图。 */
    static Set<String> freeze(Set<String> source) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}