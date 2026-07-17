package com.pixflow.harness.loop;

import com.pixflow.harness.hooks.payload.RuntimeScope;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.harness.permission.PermissionPlanMode;
import com.pixflow.harness.permission.PermissionPrincipal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 单会话可变运行态，回合内线程封闭。
 *
 * <p>loop 是该类型的唯一持有者；context / tools / permission 都不直接引用它
 * （避免反向依赖）。loop 在边界把它翻译为 {@code ToolExecutionContext} /
 * {@code PermissionContext} 等下游协作对象。
 *
 * <p>关键字段：
 * <ul>
 *   <li>{@link #usage}：本回合累计 token 用量，</li>
 *   <li>{@link #iterationCount}：仅用于 trace 标注与调试，不做任何 capping（无 maxTurns），</li>
 *   <li>{@link #hasAttemptedReactiveCompact}：本回合是否已触发反应式压缩（防抖），</li>
 *   <li>{@link #maxOutputRecoveryCount}：输出截断 recovery 次数，</li>
 *   <li>{@link #hasEscalatedMaxOutput}：是否已抬高 max output tokens（单次），</li>
 *   <li>{@link #lastTransition}：上一轮续轮原因（首轮为 null），</li>
 *   <li>{@link #conversationId}：会话身份（PixFlow 无独立 sessionId），</li>
 *   <li>{@link #runtimeScope}：主 / 子 Agent 维度（来自 harness-hooks），</li>
 *   <li>{@link #turnNo}：回合序号，由 {@code AgentLoop.runLoop} 入口自增，</li>
 *   <li>{@link #traceId}：本回合的 traceId（由调用方注入），</li>
 *   <li>{@link #metadata}：开放扩展位，承载 {@code subagent} / {@code readOnlyAgent} /
 *       {@code deniedTools} / {@code disabledTools} / {@code hiddenTools} /
 *       {@code planMode} / {@code modelRequestOverrides} / {@code isForkChild}。</li>
 * </ul>
 *
 * <p>线程封闭约定：调用方为每个 conversationId 在请求入口构造一个新实例；
 * loop 内部不维护 conversationId → RuntimeState 的映射。
 */
public final class RuntimeState {
    private TokenUsage usage = TokenUsage(0, 0, 0);
    private int iterationCount;
    private boolean hasAttemptedReactiveCompact;
    private int maxOutputRecoveryCount;
    private boolean hasEscalatedMaxOutput;
    private TransitionReason lastTransition;
    private String conversationId;
    private RuntimeScope runtimeScope;
    private PermissionPrincipal permissionPrincipal;
    private PermissionPlanMode permissionPlanMode = PermissionPlanMode.OFF;
    private int turnNo;
    private String traceId;
    private final Map<String, Object> metadata = new HashMap<>();

    /**
     * 累加本轮模型调用的 token 用量。
     */
    public void addUsage(TokenUsage delta) {
        if (delta == null) {
            return;
        }
        long prompt = this.usage.promptTokens() + delta.promptTokens();
        long completion = this.usage.completionTokens() + delta.completionTokens();
        long total = this.usage.totalTokens() + delta.totalTokens();
        this.usage = TokenUsage(prompt, completion, total);
    }

    /**
     * 记录一次 transition 续轮原因；emit TRANSITION 事件由 AgentLoop 在调用点负责。
     */
    public void setTransition(TransitionReason reason) {
        this.lastTransition = reason;
    }

    public TokenUsage usage() {
        return usage;
    }

    public int iterationCount() {
        return iterationCount;
    }

    /** 仅用于 trace 标注；不做 capping。 */
    public void incrementIteration() {
        this.iterationCount++;
    }

    public boolean hasAttemptedReactiveCompact() {
        return hasAttemptedReactiveCompact;
    }

    public void markReactiveCompactAttempted() {
        this.hasAttemptedReactiveCompact = true;
    }

    public int maxOutputRecoveryCount() {
        return maxOutputRecoveryCount;
    }

    public void incrementMaxOutputRecovery() {
        this.maxOutputRecoveryCount++;
    }

    public boolean hasEscalatedMaxOutput() {
        return hasEscalatedMaxOutput;
    }

    public void markMaxOutputEscalated() {
        this.hasEscalatedMaxOutput = true;
    }

    public TransitionReason lastTransition() {
        return lastTransition;
    }

    public String conversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public RuntimeScope runtimeScope() {
        return runtimeScope;
    }

    public void setRuntimeScope(RuntimeScope runtimeScope) {
        this.runtimeScope = runtimeScope;
    }

    public PermissionPrincipal permissionPrincipal() {
        return permissionPrincipal;
    }

    public void setPermissionPrincipal(PermissionPrincipal permissionPrincipal) {
        this.permissionPrincipal = permissionPrincipal;
    }

    public PermissionPlanMode permissionPlanMode() {
        return permissionPlanMode;
    }

    public void setPermissionPlanMode(PermissionPlanMode permissionPlanMode) {
        this.permissionPlanMode = permissionPlanMode == null ? PermissionPlanMode.ACTIVE : permissionPlanMode;
    }

    public int turnNo() {
        return turnNo;
    }

    public void setTurnNo(int turnNo) {
        this.turnNo = turnNo;
    }

    public String traceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    /**
     * metadata 是开放扩展位。读取方应优先用 {@link #metadataOrDefault(String, Object)}
     * 携带默认值；写入方应只放入字符串可序列化的简单值。
     */
    public Map<String, Object> metadata() {
        return Collections.unmodifiableMap(metadata);
    }

    @SuppressWarnings("unchecked")
    public <T> T metadataOrDefault(String key, T defaultValue) {
        Object value = metadata.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (defaultValue instanceof java.util.Collection<?> && value instanceof java.util.Collection<?>) {
            return (T) value;
        }
        if (defaultValue instanceof Map<?, ?> && value instanceof Map<?, ?>) {
            return (T) value;
        }
        if (defaultValue != null && !defaultValue.getClass().isInstance(value)) {
            return defaultValue;
        }
        return (T) value;
    }

    public void putMetadata(String key, Object value) {
        String normalizedKey = MetadataValues.validateKey(key);
        if (value == null) {
            metadata.remove(normalizedKey);
            return;
        }
        metadata.put(normalizedKey, MetadataValues.normalizeValue(value));
    }

    public void putAllMetadata(Map<String, Object> additions) {
        if (additions != null) {
            additions.forEach(this::putMetadata);
        }
    }

    /** 工厂：构造一个带默认 metadata 值的快照；metadata 字段单独初始化以避免 record 默认值陷阱。 */
    private static TokenUsage TokenUsage(long p, long c, long t) {
        return new TokenUsage(p, c, t);
    }

}
