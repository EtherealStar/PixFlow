package com.pixflow.agent.memory;

import java.util.List;
import java.util.Objects;

/**
 * 召回输入信号 A-E。
 *
 * <p>对应 {@code agent.md §6.1}：
 * <ul>
 *   <li>A: userMessage</li>
 *   <li>B: attachedPackageId</li>
 *   <li>C: currentPackageSkuIds</li>
 *   <li>D: recentAssistantMessages[N]</li>
 *   <li>E: mentionedSkuIds</li>
 * </ul>
 *
 * <p>不可变 record；构造期校验。
 */
public record MemoryRecallSignal(
        String userMessage,
        String attachedPackageId,
        List<String> currentPackageSkuIds,
        List<String> recentAssistantMessages,
        List<String> mentionedSkuIds
) {

    public MemoryRecallSignal {
        Objects.requireNonNull(userMessage, "userMessage");
        currentPackageSkuIds = currentPackageSkuIds == null ? List.of() : List.copyOf(currentPackageSkuIds);
        recentAssistantMessages = recentAssistantMessages == null ? List.of() : List.copyOf(recentAssistantMessages);
        mentionedSkuIds = mentionedSkuIds == null ? List.of() : List.copyOf(mentionedSkuIds);
    }
}