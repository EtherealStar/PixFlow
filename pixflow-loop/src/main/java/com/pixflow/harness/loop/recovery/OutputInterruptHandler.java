package com.pixflow.harness.loop.recovery;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.store.MessageStore;
import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.harness.loop.TransitionReason;
import com.pixflow.harness.loop.config.LoopProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 输出截断（{@code StopReason.LENGTH} 或 {@code outputInterrupted} 信号）恢复处理器。
 *
 * <p>三段决策严格按 {@code loop.md §十}：
 * <ul>
 *   <li>首次（{@code !hasEscalatedMaxOutput}）→ 抬高 maxOutputTokens → ESCALATE，</li>
 *   <li>再次且 {@code maxOutputRecoveryCount < maxOutputRecoveryLimit} →
 *       追加截断 assistant + 续写 prompt → RECOVERY，</li>
 *   <li>超限 → Abort，由 AgentLoop 走不可恢复异常路径（TurnTrace.abort + ErrorRecorder.record + 上抛）。</li>
 * </ul>
 */
public final class OutputInterruptHandler {

    private final LoopProperties properties;

    public OutputInterruptHandler(LoopProperties properties) {
        this.properties = properties == null ? new LoopProperties() : properties;
    }

    /**
     * @param state 当前回合运行态（防抖位 + recovery 计数器写在 state 上）
     * @param store 当前回合消息存储（用于 append 截断 assistant 与续写 prompt）
     * @param assistantPartialText 本轮已 emit 的 partial 文本，作为截断 assistant 的内容
     * @param continuationPrompt 续写 prompt（由调用方注入；缺省为内置标准续写指令）
     */
    public GateDecision onOutputInterrupted(RuntimeState state,
                                            MessageStore store,
                                            String assistantPartialText,
                                            String continuationPrompt) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(store, "store");

        if (!state.hasEscalatedMaxOutput()) {
            // 首次 → ESCALATE：抬高 maxOutputTokens 后重试本迭代
            int escalated = properties.escalatedMaxOutputTokens();
            Map<String, Object> overrides = new LinkedHashMap<>();
            overrides.put("maxOutputTokens", escalated);
            state.putAllMetadata(modelRequestOverrides(state, overrides));
            state.markMaxOutputEscalated();
            return new GateDecision.Retry(TransitionReason.MAX_OUTPUT_TOKENS_ESCALATE);
        }

        int limit = properties.maxOutputRecoveryLimit();
        if (state.maxOutputRecoveryCount() < limit) {
            // 再次 → RECOVERY：append 截断 assistant + 续写 prompt 后 continue
            String truncated = safeText(assistantPartialText);
            String trailer = "[truncated — continuation requested]";
            Message assistantTrunc = Message.assistant(
                    truncated.isEmpty() ? trailer : truncated + "\n" + trailer);
            store.appendAssistant(assistantTrunc);
            store.appendUser(safeText(continuationPrompt));
            state.incrementMaxOutputRecovery();
            return new GateDecision.ContinueAfterAppend(TransitionReason.MAX_OUTPUT_TOKENS_RECOVERY);
        }

        // 超限 → abort
        PixFlowException error = new PixFlowException(
                com.pixflow.harness.loop.error.LoopErrorCode.LOOP_RUNTIME_STATE_CORRUPTED,
                "Output recovery exceeded limit " + limit);
        return new GateDecision.Abort(error);
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> modelRequestOverrides(RuntimeState state,
                                                              Map<String, Object> additions) {
        Object existing = state.metadata().get("modelRequestOverrides");
        Map<String, Object> base = existing instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m)
                : new LinkedHashMap<>();
        base.putAll(additions);
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("modelRequestOverrides", base);
        return container;
    }
}