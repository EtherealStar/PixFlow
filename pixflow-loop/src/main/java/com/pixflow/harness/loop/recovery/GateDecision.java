package com.pixflow.harness.loop.recovery;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.loop.TransitionReason;

/**
 * 错误恢复关口的决策结果（sealed）。
 *
 * <p>loop 主循环根据该决策决定下一步：
 * <ul>
 *   <li>{@link Retry}：不 append assistant，回到本轮 while 头重试（continue），</li>
 *   <li>{@link ContinueAfterAppend}：append 截断 assistant + 续写 prompt 后 continue，</li>
 *   <li>{@link Abort}：把异常向上抛，由 web 层归一化（TurnTrace.abort 由 AgentLoop 注入）。</li>
 * </ul>
 */
public sealed interface GateDecision
        permits GateDecision.Retry, GateDecision.ContinueAfterAppend, GateDecision.Abort {

    record Retry(TransitionReason reason) implements GateDecision {
        public Retry {
            if (reason == null) {
                throw new IllegalArgumentException("reason must not be null");
            }
        }
    }

    record ContinueAfterAppend(TransitionReason reason) implements GateDecision {
        public ContinueAfterAppend {
            if (reason == null) {
                throw new IllegalArgumentException("reason must not be null");
            }
        }
    }

    record Abort(PixFlowException error) implements GateDecision {
        public Abort {
            if (error == null) {
                throw new IllegalArgumentException("error must not be null");
            }
        }
    }
}