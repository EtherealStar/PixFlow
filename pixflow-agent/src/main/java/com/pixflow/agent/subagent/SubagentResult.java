package com.pixflow.agent.subagent;

import com.pixflow.infra.ai.model.TokenUsage;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Subagent 执行结果（不可变 record）。
 *
 * <p>对应 {@code agent.md §8.1}：父 agent 工具 handler 把 child 跑完的结果
 * 回填到 {@code ToolExecutionResult}。
 *
 * <p>错误处理：child 抛不可恢复异常 → {@code isError=true} + {@code errorMessage=脱敏}；
 * 不冒泡到父 loop（与 {@code agent.md §8.7} 一致）。
 */
public record SubagentResult(
        String finalText,
        TokenUsage usage,
        int toolResultCount,
        List<Map<String, Object>> childToolSpans,
        String errorMessage,
        boolean isError
) {

    public SubagentResult {
        finalText = finalText == null ? "" : finalText;
        usage = usage == null ? new TokenUsage(0, 0, 0) : usage;
        childToolSpans = childToolSpans == null ? List.of() : List.copyOf(childToolSpans);
    }

    public static SubagentResult ok(String finalText, TokenUsage usage, int toolResultCount) {
        return new SubagentResult(finalText, usage, toolResultCount, List.of(), null, false);
    }

    public static SubagentResult error(String errorMessage) {
        return new SubagentResult("", new TokenUsage(0, 0, 0), 0, List.of(),
                Objects.requireNonNull(errorMessage, "errorMessage"), true);
    }
}