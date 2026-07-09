package com.pixflow.harness.loop;

/**
 * 主循环续轮 / 终止语义枚举。
 *
 * <p>续轮只看 {@link #TOOL_USE} 与 {@link #COMPLETED}；其余为错误恢复或速率重试
 * 信号，由 loop 仅记录、退避或重试由 infra/ai 或本模块的恢复处理器承担。
 */
public enum TransitionReason {
    /** 本轮 assistant 产生了实际工具调用 → 执行后继续循环。 */
    TOOL_USE,
    /** 本轮无工具调用，自然结束 → 返回 final text 并 commit trace。 */
    COMPLETED,
    /** ChatModelClient.stream 内部触发模型重试（退避在 infra/ai，loop 仅记录）。 */
    RATE_LIMIT_RETRY,
    /** CONTEXT_LIMIT 首次 → 已触发 reactiveCompact，重试本迭代（不 append assistant）。 */
    REACTIVE_COMPACT_RETRY,
    /** 首次输出截断（StopReason.LENGTH）→ 已抬高 maxOutputTokens，重试本迭代。 */
    MAX_OUTPUT_TOKENS_ESCALATE,
    /** 再次输出截断且未超 recovery 上限 → 追加截断 assistant + 续写 prompt 后 continue。 */
    MAX_OUTPUT_TOKENS_RECOVERY
}
