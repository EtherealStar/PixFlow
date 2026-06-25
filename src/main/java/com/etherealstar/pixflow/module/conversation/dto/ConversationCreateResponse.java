package com.etherealstar.pixflow.module.conversation.dto;

/**
 * 创建对话响应（需求 5.1）。
 *
 * @param conversationId 新建对话的 id
 */
public record ConversationCreateResponse(long conversationId) {

    public static ConversationCreateResponse of(long conversationId) {
        return new ConversationCreateResponse(conversationId);
    }
}
