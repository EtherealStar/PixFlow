package com.pixflow.module.conversation.app;

/** 为跨上下文持久快照提供当前会话标题，不暴露 Conversation 持久化实体。 */
public interface ConversationTitleQuery {
    String titleSnapshot(String conversationId);
}
