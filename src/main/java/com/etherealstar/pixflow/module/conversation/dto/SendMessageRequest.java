package com.etherealstar.pixflow.module.conversation.dto;

/**
 * 发送消息请求体（需求 5.3、5.4、5.7、5.8）。
 *
 * @param content           消息内容（非空白、长度 ≤ 4000）
 * @param attachedPackageId 关联素材包 id，可空；非空时该素材包须存在且状态为就绪
 */
public record SendMessageRequest(String content, Long attachedPackageId) {
}
