package com.pixflow.harness.loop.trace;

import com.pixflow.harness.eval.model.RuntimeScope;

/**
 * 双 {@code RuntimeScope} 翻译器。
 *
 * <p>两个不同的 RuntimeScope 类型在仓库中并存：
 * <ul>
 *   <li>{@code com.pixflow.harness.hooks.payload.RuntimeScope} — HookPayload 公共基段，
 *       形如 {@code record(boolean subagent, String subagentType)}；</li>
 *   <li>{@code com.pixflow.harness.eval.model.RuntimeScope} — trace 持久化维度的枚举
 *       （{@code MAIN / SUB_AGENT / WORKER}）。</li>
 * </ul>
 *
 * <p>loop 必须同时使用两者（派发 hook 时用 hooks 版本，调 {@code TraceRecorder.begin}
 * 时用 eval 版本），翻译规则集中在本类内，避免 loop 主循环持有两份类型造成歧义。
 *
 * <p>调用方在使用本类时建议这样 import：
 * <pre>
 * import com.pixflow.harness.hooks.payload.RuntimeScope;        // 主用
 * import com.pixflow.harness.loop.trace.RuntimeScopeTranslator;
 * </pre>
 * eval 版本全程用 {@code RuntimeScopeTranslator.toEval(hooksScope)} 取得。
 */
public final class RuntimeScopeTranslator {

    private RuntimeScopeTranslator() {
    }

    /**
     * hooks → eval。{@code WORKER} 暂未启用，subagentType 落在 hooks 维度。
     */
    public static RuntimeScope toEval(com.pixflow.harness.hooks.payload.RuntimeScope hooks) {
        if (hooks == null || !hooks.subagent()) {
            return RuntimeScope.MAIN;
        }
        if ("worker".equalsIgnoreCase(hooks.subagentType())) {
            return RuntimeScope.WORKER;
        }
        return RuntimeScope.SUB_AGENT;
    }

    /**
     * eval → hooks。{@code MAIN} → main；{@code SUB_AGENT} → subagent("subagent")；
     * {@code WORKER} → subagent("worker")。
     */
    public static com.pixflow.harness.hooks.payload.RuntimeScope toHooks(RuntimeScope eval) {
        if (eval == null || eval == RuntimeScope.MAIN) {
            return com.pixflow.harness.hooks.payload.RuntimeScope.main();
        }
        if (eval == RuntimeScope.WORKER) {
            return com.pixflow.harness.hooks.payload.RuntimeScope.of("worker");
        }
        return com.pixflow.harness.hooks.payload.RuntimeScope.of("subagent");
    }
}
