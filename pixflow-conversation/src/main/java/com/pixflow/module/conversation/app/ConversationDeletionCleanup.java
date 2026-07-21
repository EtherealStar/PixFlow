package com.pixflow.module.conversation.app;

/** Conversation 真实删除时清理其拥有的附属运行态。 */
@FunctionalInterface
public interface ConversationDeletionCleanup {
    void delete(String conversationId);
}
