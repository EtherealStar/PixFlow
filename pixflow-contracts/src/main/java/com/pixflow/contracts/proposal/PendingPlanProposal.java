package com.pixflow.contracts.proposal;

import java.time.Instant;

/**
 * DAG 与 imagegen 共享的待确认提案载体。
 *
 * <p>{@code payloadJson} 是提案属主模块序列化后的 JSON, contracts 层不理解其结构。
 */
public record PendingPlanProposal(
        String planType,
        String payloadJson,
        String conversationId,
        String packageId,
        String toolCallId,
        Instant createdAt) {

    public PendingPlanProposal {
        if (planType == null || planType.isBlank()) {
            throw new IllegalArgumentException("planType is required");
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("payloadJson is required");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId is required");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
    }
}
