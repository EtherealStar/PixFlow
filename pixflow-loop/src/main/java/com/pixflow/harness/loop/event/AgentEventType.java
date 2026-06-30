package com.pixflow.harness.loop.event;

/**
 * loop 事件类型枚举。
 *
 * <p>精简集合：不含纯 UI 事件（如 interaction_started），且 loop 不 emit
 * error 事件 —— 不可恢复错误经 {@code ErrorRecorder} 落盘后向上抛，由 web 层
 * 统一归一化为 HTTP / SSE error 帧。
 */
public enum AgentEventType {
    /** LLM token 流式增量（SSE 主体）。 */
    ASSISTANT_DELTA,
    /** 一次 assistant 消息完成。 */
    ASSISTANT_MESSAGE_COMPLETED,
    /** 解析出工具调用（含 name / input 预览）。 */
    TOOL_CALL_READY,
    /** 工具开始执行。 */
    TOOL_STARTED,
    /** 工具结果（含引用 / preview，非大字节）。 */
    TOOL_RESULT,
    /** 一次 transition 发生。 */
    TRANSITION,
    /** 回合自然结束（final text）。 */
    COMPLETED
}