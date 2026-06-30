package com.pixflow.agent.subagent;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Subagent 启动请求（不可变 record）。
 *
 * <p>对应 {@code agent.md §8}：
 * 父 → child 的所有必要参数；child runtime 按 type 装配工具集。
 *
 * @param type              Subagent 类型
 * @param prompt            child 任务指令
 * @param parentToolCallId  父回合的 tool call id（用于 trace 关联）
 * @param parentConversationId 父会话 ID
 * @param imageIds          视觉理解子 Agent 的 image id 列表（仅 VISION）
 * @param focus             摘要聚焦指令（仅 SUMMARIZATION）
 * @param summaryInstructions 来自 PreCompact hook 的指令（仅 SUMMARIZATION）
 * @param metadata          透传给 child 的额外元信息
 */
public record SubagentRequest(
        SubagentType type,
        String prompt,
        String parentToolCallId,
        String parentConversationId,
        List<String> imageIds,
        String focus,
        String summaryInstructions,
        Map<String, Object> metadata
) {

    public SubagentRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(prompt, "prompt");
        imageIds = imageIds == null ? List.of() : List.copyOf(imageIds);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static SubagentRequest vision(String parentConversationId, String parentToolCallId,
                                          List<String> imageIds, String prompt) {
        return new SubagentRequest(SubagentType.VISION, prompt, parentToolCallId,
                parentConversationId, imageIds, null, null, Map.of());
    }

    public static SubagentRequest explore(String parentConversationId, String parentToolCallId, String prompt) {
        return new SubagentRequest(SubagentType.EXPLORE, prompt, parentToolCallId,
                parentConversationId, List.of(), null, null, Map.of());
    }

    public static SubagentRequest summary(String parentConversationId, String parentToolCallId,
                                           String prompt, String focus, String summaryInstructions) {
        return new SubagentRequest(SubagentType.SUMMARIZATION, prompt, parentToolCallId,
                parentConversationId, List.of(), focus, summaryInstructions, Map.of());
    }

    public static SubagentRequest sessionMemoryExtraction(String parentConversationId, String parentToolCallId,
                                                           String prompt) {
        return new SubagentRequest(SubagentType.SESSION_MEMORY_EXTRACTION, prompt, parentToolCallId,
                parentConversationId, List.of(), null, null, Map.of());
    }
}