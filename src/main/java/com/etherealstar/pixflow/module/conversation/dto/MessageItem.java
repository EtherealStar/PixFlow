package com.etherealstar.pixflow.module.conversation.dto;

import com.etherealstar.pixflow.module.conversation.entity.Message;

import java.time.LocalDateTime;

/**
 * 消息项（消息历史与发送消息响应复用，需求 5.3、5.4、5.5）。
 *
 * @param id                消息 id
 * @param conversationId    所属对话 id
 * @param role              角色（{@code user} / {@code assistant}）
 * @param content           消息内容
 * @param attachedPackageId 关联素材包 id，可空
 * @param taskId            该消息触发的处理任务 id，可空（需求 5.6）
 * @param createdAt         创建时间
 */
public record MessageItem(Long id, Long conversationId, String role, String content,
                          Long attachedPackageId, Long taskId, LocalDateTime createdAt) {

    public static MessageItem from(Message message) {
        return new MessageItem(
                message.getId(),
                message.getConversationId(),
                message.getRole(),
                message.getContent(),
                message.getAttachedPackageId(),
                message.getTaskId(),
                message.getCreatedAt());
    }
}
