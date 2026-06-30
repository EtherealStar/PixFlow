package com.pixflow.module.conversation.progress;

import com.pixflow.common.progress.ProgressNotifier;
import com.pixflow.module.conversation.config.ConversationProperties;

public class ConversationProgressBridge {
    private final ProgressNotifier progressNotifier;
    private final ConversationProperties properties;

    public ConversationProgressBridge(ProgressNotifier progressNotifier, ConversationProperties properties) {
        this.progressNotifier = progressNotifier;
        this.properties = properties;
    }

    public void onProgress(String conversationId, String taskId, Object event) {
        progressNotifier.publish(channel(conversationId, taskId), event);
    }

    private String channel(String conversationId, String taskId) {
        return properties.getProgress().getTopicPrefix() + "-" + conversationId + "-" + taskId;
    }
}
