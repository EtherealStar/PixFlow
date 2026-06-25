package com.etherealstar.pixflow.module.conversation.dto;

import com.etherealstar.pixflow.module.conversation.entity.Conversation;

import java.time.LocalDateTime;

/**
 * 对话列表项（需求 5.1 列表展示）。
 *
 * @param id        对话 id
 * @param title     标题（取首条消息前 20 字，未发送消息时可空）
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ConversationListItem(Long id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static ConversationListItem from(Conversation conversation) {
        return new ConversationListItem(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}
