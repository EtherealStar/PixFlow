package com.etherealstar.pixflow.module.task.dto;

/**
 * 确认执行请求体（需求 7.1、12.6）。
 *
 * <p>对应 {@code POST /api/conversation/{conversationId}/confirm} 的请求体。前端在收到 dagPreview 后，
 * 携带回传的 {@code dagJson} 与目标 {@code packageId} 发起确认；服务端不信任该回传结构，将由
 * {@link com.etherealstar.pixflow.module.dag.validator.DagValidator} 重新独立校验（需求 7.1）。</p>
 *
 * @param dagJson   待执行的 DAG JSON（前端回传，服务端重新校验）
 * @param packageId 处理目标素材包 id（须存在且状态为就绪）
 */
public record ConfirmRequest(String dagJson, Long packageId) {
}
