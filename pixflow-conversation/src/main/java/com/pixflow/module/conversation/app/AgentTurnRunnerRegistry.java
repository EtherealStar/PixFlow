package com.pixflow.module.conversation.app;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.loop.AgentTurnRunner;
import com.pixflow.module.conversation.error.ConversationErrorCode;

/**
 * conversation 模块对 {@link AgentTurnRunner} SPI 的运行时注册/选择。
 *
 * <p>设计动机：
 * <ul>
 *   <li>{@link AgentTurnRunner} 接口由 harness-loop 拥有，conversation 与 agent
 *       两模块共同可见，避免编译期反向依赖</li>
 *   <li>agent 模块把 {@code AgentOrchestrator} 暴露为 {@link AgentTurnRunner} bean；
 *       本注册器通过 {@link #of(AgentTurnRunner)} 接收 Spring 容器中可选的实现 bean，
 *       选不到时回退 unavailable 默认值</li>
 * </ul>
 *
 * <p>这种"弱耦合 + 运行时选择"模式让 conversation 模块始终独立可装配，agent 模块可选 —
 * production 环境两个模块都在，{@code AgentTurnRunner} bean 被选为生产实现；
 * 测试 / 单模块场景只有 conversation 时，回退到 unavailable。
 */
public interface AgentTurnRunnerRegistry {

    /**
     * 解析当前回合的 {@link AgentTurnRunner}：
     * <ul>
     *   <li>若 Spring 容器里有 {@code AgentTurnRunner} bean，包装为生产实现</li>
     *   <li>否则返回 unavailable 占位（抛 {@link ConversationErrorCode#TURN_RUNNER_UNAVAILABLE}）</li>
     * </ul>
     */
    AgentTurnRunner resolve();

    /**
     * unavailable 默认实现：用于测试 / 单模块场景 / agent 模块未装配。
     */
    AgentTurnRunner UNAVAILABLE = (request, sink) -> {
        throw new PixFlowException(ConversationErrorCode.TURN_RUNNER_UNAVAILABLE,
                "agent turn runner is not configured");
    };

    /**
     * 工厂方法：根据 Spring 容器中是否存在 {@code AgentTurnRunner} bean 返回对应实现。
     *
     * @param agentTurnRunner 可选；为 null 时回退到 unavailable
     */
    static AgentTurnRunnerRegistry of(AgentTurnRunner agentTurnRunner) {
        if (agentTurnRunner == null) {
            return () -> UNAVAILABLE;
        }
        AgentTurnRunner runner = agentTurnRunner;
        return () -> runner;
    }
}
