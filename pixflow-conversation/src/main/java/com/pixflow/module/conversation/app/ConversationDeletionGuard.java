package com.pixflow.module.conversation.app;

/** 由组合根实现的跨上下文删除保护，不把 Task 查询细节带入 Conversation。 */
@FunctionalInterface
public interface ConversationDeletionGuard {
    void requireDeletable(long administratorId, String conversationId);
}
