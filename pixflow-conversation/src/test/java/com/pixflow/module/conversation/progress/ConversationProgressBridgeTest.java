package com.pixflow.module.conversation.progress;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.conversation.config.ConversationProperties;
import org.junit.jupiter.api.Test;

class ConversationProgressBridgeTest {
    @Test
    void publishesHyphenatedTaskProgressTopic() {
        ConversationProperties properties = new ConversationProperties();
        CapturingNotifier notifier = new CapturingNotifier();
        ConversationProgressBridge bridge = new ConversationProgressBridge(notifier, properties);

        bridge.onProgress("c1", "t1", "event");

        assertThat(notifier.channel).isEqualTo("task-progress-c1-t1");
        assertThat(notifier.event).isEqualTo("event");
    }

    private static class CapturingNotifier implements com.pixflow.common.progress.ProgressNotifier {
        String channel;
        Object event;

        @Override
        public void publish(String channel, Object event) {
            this.channel = channel;
            this.event = event;
        }
    }
}
