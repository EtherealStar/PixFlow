package com.pixflow.harness.loop.recovery;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.context.compaction.ContextCompactionService;
import com.pixflow.harness.context.store.MessageStore;
import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.harness.loop.TransitionReason;
import com.pixflow.harness.loop.config.LoopProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * CONTEXT_LIMIT 反应式压缩门：只在「本回合首次」时触发，避免同回合内重复 compact。
 *
 * <p>防抖由 {@link RuntimeState#hasAttemptedReactiveCompact()} 维护（loop 自维护，
 * context 不感知）。再次同错（防抖位已置）由 AgentLoop 改走 {@link OutputInterruptHandler}
 * 的 ESCALATE → RECOVERY 兜底。
 *
 * <p>本类不感知 infra/ai、tools、permission、eval —— 仅消费 {@link ContextCompactionService}
 * 与 {@link MessageStore} 两个 SPI。
 */
public final class ReactiveCompactionGate {

    private final ContextCompactionService compactionService;

    private final LoopProperties properties;

    public ReactiveCompactionGate(ContextCompactionService compactionService,
                                  LoopProperties properties) {
        this.compactionService = Objects.requireNonNull(compactionService, "compactionService");
        this.properties = properties == null ? new LoopProperties() : properties;
    }

    /**
     * 在 AgentLoop 检测到 {@link com.pixflow.common.error.ErrorCategory#CONTEXT_LIMIT}
     * 时调用。返回值参见 {@link GateDecision}。
     *
     * @param state 当前回合运行态（防抖位写在 state 上）
     * @param store 当前回合消息存储（用于 replaceForCompaction）
     * @param error 触发 CONTEXT_LIMIT 的 PixFlowException
     */
    public GateDecision onContextLimit(RuntimeState state,
                                       MessageStore store,
                                       PixFlowException error) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(store, "store");
        if (state.hasAttemptedReactiveCompact()) {
            // 防抖：同回合不再尝试 reactive，把决策上交给 OutputInterruptHandler
            return new GateDecision.Abort(error);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", properties.compactionSource().isBlank()
                ? "loop.reactive" : properties.compactionSource());
        compactionService.reactiveCompact(store, error, metadata);
        state.markReactiveCompactAttempted();
        return new GateDecision.Retry(TransitionReason.REACTIVE_COMPACT_RETRY);
    }
}
