package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.ai.model.TokenUsage;
import org.junit.jupiter.api.Test;

/**
 * {@link RuntimeState} 字段语义测试：累加 / 防抖位 / 计数器。
 */
class RuntimeStateTest {

    @Test
    void addUsageAccumulates() {
        RuntimeState state = new RuntimeState();
        state.addUsage(new TokenUsage(10, 5, 15));
        state.addUsage(new TokenUsage(20, 10, 30));
        assertThat(state.usage().promptTokens()).isEqualTo(30);
        assertThat(state.usage().completionTokens()).isEqualTo(15);
        assertThat(state.usage().totalTokens()).isEqualTo(45);
    }

    @Test
    void addUsageIgnoresNull() {
        RuntimeState state = new RuntimeState();
        state.addUsage(null);
        assertThat(state.usage().promptTokens()).isZero();
    }

    @Test
    void iterationCountIncrementsWithoutCapping() {
        RuntimeState state = new RuntimeState();
        for (int i = 0; i < 100; i++) {
            state.incrementIteration();
        }
        assertThat(state.iterationCount()).isEqualTo(100);
    }

    @Test
    void reactiveCompactDebounceIsOneShot() {
        RuntimeState state = new RuntimeState();
        assertThat(state.hasAttemptedReactiveCompact()).isFalse();
        state.markReactiveCompactAttempted();
        assertThat(state.hasAttemptedReactiveCompact()).isTrue();
        // 再次 mark 是 idempotent
        state.markReactiveCompactAttempted();
        assertThat(state.hasAttemptedReactiveCompact()).isTrue();
    }

    @Test
    void maxOutputRecoveryAndEscalateFlags() {
        RuntimeState state = new RuntimeState();
        assertThat(state.hasEscalatedMaxOutput()).isFalse();
        state.markMaxOutputEscalated();
        assertThat(state.hasEscalatedMaxOutput()).isTrue();
        assertThat(state.maxOutputRecoveryCount()).isZero();
        state.incrementMaxOutputRecovery();
        state.incrementMaxOutputRecovery();
        assertThat(state.maxOutputRecoveryCount()).isEqualTo(2);
    }

    @Test
    void metadataDefaultAndOverride() {
        RuntimeState state = new RuntimeState();
        assertThat(state.<String>metadataOrDefault("missing", "default")).isEqualTo("default");
        state.putMetadata("deniedTools", java.util.Set.of("rm", "kill"));
        assertThat(state.<java.util.Set<String>>metadataOrDefault("deniedTools", java.util.Set.of()))
                .contains("rm", "kill");
        // metadata view 是不可变
        assertThat(state.metadata()).containsKey("deniedTools");
        assertThatThrownBy(() -> state.metadata().put("k", "v"));
    }

    @Test
    void runtimeScopeDefaultsToMain() {
        RuntimeState state = new RuntimeState();
        assertThat(state.runtimeScope().subagent()).isFalse();
    }

    @Test
    void setTransitionTracksLastReason() {
        RuntimeState state = new RuntimeState();
        state.setTransition(TransitionReason.TOOL_USE);
        assertThat(state.lastTransition()).isEqualTo(TransitionReason.TOOL_USE);
        state.setTransition(TransitionReason.COMPLETED);
        assertThat(state.lastTransition()).isEqualTo(TransitionReason.COMPLETED);
    }
}