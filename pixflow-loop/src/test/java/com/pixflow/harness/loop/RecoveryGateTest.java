package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.context.budget.ConservativeTokenEstimator;
import com.pixflow.harness.context.budget.ContextBudgetConfig;
import com.pixflow.harness.context.budget.ContextBudgetService;
import com.pixflow.harness.context.compaction.CompactionConfig;
import com.pixflow.harness.context.compaction.ContextCompactionService;
import com.pixflow.harness.context.store.MessageStore;
import com.pixflow.harness.loop.config.LoopProperties;
import com.pixflow.harness.loop.recovery.GateDecision;
import com.pixflow.harness.loop.recovery.OutputInterruptHandler;
import com.pixflow.harness.loop.recovery.ReactiveCompactionGate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 恢复处理器单元测试：
 * <ul>
 *   <li>{@link ReactiveCompactionGate} 首次触发 + 防抖；</li>
 *   <li>{@link OutputInterruptHandler} ESCALATE → RECOVERY → abort 三段路径。</li>
 * </ul>
 */
class RecoveryGateTest {

    @Test
    void reactiveCompactionGateReturnsRetryOnFirstTimeAndAbortsOnSecond() {
        ContextCompactionService compaction = new ContextCompactionService(
                new ContextBudgetService(ContextBudgetConfig.defaults(),
                        new ConservativeTokenEstimator(), null),
                new ConservativeTokenEstimator(), null, CompactionConfig.defaults());
        ReactiveCompactionGate gate = new ReactiveCompactionGate(compaction, new LoopProperties());
        RuntimeState state = new RuntimeState();
        MessageStore store = new MessageStore();
        PixFlowException err = new PixFlowException(
                CommonErrorCode.CONTEXT_LIMIT_EXCEEDED, "ctx-limit");

        GateDecision first = gate.onContextLimit(state, store, err);
        assertThat(first).isInstanceOf(GateDecision.Retry.class);
        assertThat(((GateDecision.Retry) first).reason()).isEqualTo(TransitionReason.REACTIVE_COMPACT_RETRY);
        assertThat(state.hasAttemptedReactiveCompact()).isTrue();

        GateDecision second = gate.onContextLimit(state, store, err);
        assertThat(second).isInstanceOf(GateDecision.Abort.class);
    }

    @Test
    void outputInterruptHandlerEscalatesOnFirstInterrupt() {
        LoopProperties props = new LoopProperties();
        OutputInterruptHandler handler = new OutputInterruptHandler(props);
        RuntimeState state = new RuntimeState();
        MessageStore store = new MessageStore();

        GateDecision d = handler.onOutputInterrupted(state, store, "partial", "continue?");
        assertThat(d).isInstanceOf(GateDecision.Retry.class);
        assertThat(((GateDecision.Retry) d).reason()).isEqualTo(TransitionReason.MAX_OUTPUT_TOKENS_ESCALATE);
        assertThat(state.hasEscalatedMaxOutput()).isTrue();
        // metadata.modelRequestOverrides.maxOutputTokens 应当被置
        Object overrides = state.metadata().get("modelRequestOverrides");
        assertThat(overrides).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) overrides).get("maxOutputTokens")).isEqualTo(props.escalatedMaxOutputTokens());
    }

    @Test
    void outputInterruptHandlerRecoversOnSecondInterrupt() {
        LoopProperties props = new LoopProperties();
        OutputInterruptHandler handler = new OutputInterruptHandler(props);
        RuntimeState state = new RuntimeState();
        MessageStore store = new MessageStore();

        // 首次 → escalate
        handler.onOutputInterrupted(state, store, "p1", "continue?");
        // 第二次 → recovery
        GateDecision d = handler.onOutputInterrupted(state, store, "p2", "continue?");
        assertThat(d).isInstanceOf(GateDecision.ContinueAfterAppend.class);
        assertThat(((GateDecision.ContinueAfterAppend) d).reason()).isEqualTo(TransitionReason.MAX_OUTPUT_TOKENS_RECOVERY);
        // store 应当多两条 assistant + user
        assertThat(state.maxOutputRecoveryCount()).isEqualTo(1);
        // 验证 store 中追加了 assistant + user
        var msgs = store.currentMessages();
        assertThat(msgs).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void outputInterruptHandlerAbortsAfterLimitExceeded() {
        LoopProperties props = new LoopProperties();
        props.setMaxOutputRecoveryLimit(2);
        OutputInterruptHandler handler = new OutputInterruptHandler(props);
        RuntimeState state = new RuntimeState();
        MessageStore store = new MessageStore();

        // escalate
        handler.onOutputInterrupted(state, store, "p", "c");
        // recovery 1
        handler.onOutputInterrupted(state, store, "p", "c");
        // recovery 2
        handler.onOutputInterrupted(state, store, "p", "c");
        // 第三次 → 超限 → abort
        GateDecision d = handler.onOutputInterrupted(state, store, "p", "c");
        assertThat(d).isInstanceOf(GateDecision.Abort.class);
    }

    @Test
    void gateDecisionRejectsNullReason() {
        assertThatThrownBy(() -> new GateDecision.Retry(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateDecision.ContinueAfterAppend(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateDecision.Abort(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}