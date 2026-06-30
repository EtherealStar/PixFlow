package com.pixflow.harness.loop.event;

/**
 * loop 的事件接出 SPI。同步接口 —— {@code AgentLoop} 在回合执行线程内调用 emit。
 *
 * <p>web 层把本 SPI 桥接到 {@code SseEmitter}（LLM token 流式）；任务进度的
 * WebSocket 推送是另一条独立链路（{@code module/task} + {@code common.ProgressNotifier}），
 * 不走本 sink。
 *
 * <p>实现必须线程安全（loop 主循环串行 emit，但 web 层 SseEmitter 适配器可能跨线程）。
 * 推荐实现保持实现极简（同步写 / 转发到 SSE 帧），把跨线程的工作交给下游。
 */
public interface AgentEventSink {
    void emit(AgentEvent event);

    /**
     * no-op 实现，用于测试与不需要事件流接出的场景（如子 Agent fork 内部循环）。
     */
    AgentEventSink NOOP = event -> { };
}