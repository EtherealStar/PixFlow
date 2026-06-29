package com.pixflow.module.imagegen.port;

import java.time.Instant;
import java.util.Objects;

/**
 * 待确认提案的中立载体(对齐 imagegen.md §六)。
 *
 * <p>DAG 提案与生图提案共用同一套 pending-plan 存储;{@link #planType()} 区分载荷类型,
 * {@link #payloadJson()} 是模块自行序列化的 JSON 字符串(本模块是 {@code ImagegenPlan},
 * dag 模块是 {@code DagDocument})。
 *
 * <p>由 {@code module/conversation} 在 Wave 4 落地时实现 {@link PendingPlanPort} 并持久化;
 * 序列化/反序列化时机由 conversation 决定(imagegen 仅持有中立 record)。
 */
public record PendingPlanProposal(
        String planType,
        String payloadJson,
        String conversationId,
        String packageId,
        String toolCallId,
        Instant createdAt) {

    public PendingPlanProposal {
        Objects.requireNonNull(planType, "planType");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}