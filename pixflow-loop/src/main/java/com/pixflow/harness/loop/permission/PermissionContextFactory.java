package com.pixflow.harness.loop.permission;

import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.harness.permission.PermissionContext;

/**
 * 把 {@link RuntimeState} 翻译为 {@link PermissionContext} 的 SPI。
 *
 * <p>loop 不解释 {@code RuntimeState.metadata} 的业务字段含义（{@code deniedTools} /
 * {@code disabledTools} / {@code subagent} / {@code readOnlyAgent} 等），本工厂把这些字段
 * 翻译成 {@link PermissionContext}，由 tools 执行管线消费。
 *
 * <p>默认实现 {@link DefaultPermissionContextFactory}：{@code deniedTools} 来自
 * {@code state.metadata.deniedTools}，{@code disabledTools} 来自
 * {@code state.metadata.disabledTools}，subagent 约束来自
 * {@code state.metadata.subagent}（{@code Map<String,Object>}，至少含
 * {@code agentType}，可选 {@code readOnly} / {@code allowedTools} /
 * {@code disallowedTools}）。
 */
public interface PermissionContextFactory {
    PermissionContext create(RuntimeState state);
}