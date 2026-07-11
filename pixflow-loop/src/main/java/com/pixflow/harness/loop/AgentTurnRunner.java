package com.pixflow.harness.loop;

import com.pixflow.harness.loop.event.AgentEventSink;

/**
 * 回合驱动 SPI — 由 harness-loop 拥有。
 *
 * <p>设计动机：
 * <ul>
 *   <li>本 SPI 必须在 conversation 与 agent 两个模块都可见的位置定义，
 *       否则任一方拥有都会引入编译期反向依赖</li>
 *   <li>{@code pixflow-harness-loop} 已是两者的共同上游依赖（loop 是
 *       conversation 回合驱动逻辑的边界，agent 装配层站在 loop 之上）</li>
 *   <li>conversation 模块持有 SPI 作为"边界类型"，agent 模块作为"生产实现"，
 *       通过 Spring 容器里按 SPI 类型注入完成依赖倒置</li>
 * </ul>
 *
 * <p>契约：
 * <ul>
 *   <li>{@link AgentTurnRequest} 由 conversation 构造，显式携带 conversation、prompt、附件和取消令牌；
 *       systemPrompt / toolSchemas 仍由 agent 实现方装配</li>
 *   <li>返回最终 assistant 文本（与 SSE {@code event: completed} 帧 finalText 一致）</li>
 *   <li>业务异常向上抛给 web 层归一化；取消异常保持控制流语义，不写 ErrorRecorder</li>
 * </ul>
 */
@FunctionalInterface
public interface AgentTurnRunner {
    String stream(AgentTurnRequest request, AgentEventSink sink);
}
