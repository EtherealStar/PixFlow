package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.harness.eval.model.RuntimeScope;
import com.pixflow.harness.loop.trace.RuntimeScopeTranslator;
import org.junit.jupiter.api.Test;

/**
 * {@link RuntimeScopeTranslator} 双向翻译规则。
 */
class RuntimeScopeTranslatorTest {

    @Test
    void mainHooksMapsToMainEval() {
        assertThat(RuntimeScopeTranslator.toEval(com.pixflow.harness.hooks.payload.RuntimeScope.main()))
                .isEqualTo(RuntimeScope.MAIN);
    }

    @Test
    void subagentHooksMapsToSubagentEval() {
        assertThat(RuntimeScopeTranslator.toEval(com.pixflow.harness.hooks.payload.RuntimeScope.of("vision")))
                .isEqualTo(RuntimeScope.SUB_AGENT);
    }

    @Test
    void nullHooksMapsToMainEval() {
        assertThat(RuntimeScopeTranslator.toEval(null))
                .isEqualTo(RuntimeScope.MAIN);
    }

    @Test
    void mainEvalMapsToMainHooks() {
        assertThat(RuntimeScopeTranslator.toHooks(RuntimeScope.MAIN))
                .isEqualTo(com.pixflow.harness.hooks.payload.RuntimeScope.main());
    }

    @Test
    void subagentEvalMapsToSubagentHooks() {
        assertThat(RuntimeScopeTranslator.toHooks(RuntimeScope.SUB_AGENT))
                .isEqualTo(com.pixflow.harness.hooks.payload.RuntimeScope.of("sub_agent"));
    }

    @Test
    void workerEvalMapsToWorkerHooks() {
        assertThat(RuntimeScopeTranslator.toHooks(RuntimeScope.WORKER))
                .isEqualTo(com.pixflow.harness.hooks.payload.RuntimeScope.of("worker"));
    }

    @Test
    void nullEvalMapsToMainHooks() {
        assertThat(RuntimeScopeTranslator.toHooks(null))
                .isEqualTo(com.pixflow.harness.hooks.payload.RuntimeScope.main());
    }

    @Test
    void hooksRuntimeScopeRejectsInconsistentSubagentFlag() {
        assertThatThrownBy(() -> new com.pixflow.harness.hooks.payload.RuntimeScope(true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}