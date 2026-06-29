package com.pixflow.module.imagegen.port;

import java.util.Optional;

/**
 * 待确认提案的 SPI(对齐 imagegen.md §四 / §六)。
 *
 * <p>由 {@code module/conversation} 在 Wave 4 落地时实现;imagegen 不直连 MySQL pending_plan 表。
 * 实现方需保证:
 * <ul>
 *   <li>{@link #enqueue(PendingPlanProposal)} 同 {@code (conversationId, toolCallId)} 幂等
 *       —— 重复入队返回同一 planId,不产生新 pending plan</li>
 *   <li>{@link #find(String)} 按 planId 取回原文(供 confirm 边界重算 payloadHash / count)</li>
 * </ul>
 *
 * <p>planType 约定:本模块用 {@code "IMAGEGEN"}(与 dag 的 {@code "IMAGE_DAG"} 区分)。
 */
public interface PendingPlanPort {
    /**
     * 入队待确认提案,返回 planId(同 conversationId+toolCallId 幂等)。
     */
    String enqueue(PendingPlanProposal proposal);

    /**
     * 按 planId 取回提案原文;不存在返回 {@link Optional#empty()}。
     */
    Optional<PendingPlanProposal> find(String planId);
}