package com.pixflow.harness.context.store;

import com.pixflow.harness.context.compaction.CompactionTrigger;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.model.MessageRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageStoreTest {
    @Test
    void currentMessagesReturnsImmutableSnapshot() {
        MessageStore store = new MessageStore();

        store.appendUser("hello");
        List<Message> snapshot = store.currentMessages();

        assertThatThrownBy(() -> snapshot.add(Message.user("mutate")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.currentMessages()).hasSize(1);
    }

    @Test
    void appendWritesThroughTranscriptPortWhenBound() {
        InMemoryTranscript transcript = new InMemoryTranscript();
        MessageStore store = new MessageStore(transcript);
        store.bindConversation("c1");

        store.appendUser("hello");

        assertThat(transcript.appended).hasSize(1);
        assertThat(store.currentMessages()).extracting(Message::content).containsExactly("hello");
    }

    @Test
    void appendSkillInvocationStoresTypedEventMetadata() {
        InMemoryTranscript transcript = new InMemoryTranscript();
        MessageStore store = new MessageStore(transcript);
        store.bindConversation("c1");

        Message message = store.appendSkillInvocation("search", 2, 1024);

        assertThat(message.role()).isEqualTo(MessageRole.USER);
        assertThat(message.content()).isEqualTo("[skill_invocation] search");
        assertThat(message.metadata().values())
                .containsEntry(MessageMetadata.EVENT, MessageMetadata.EVENT_SKILL_INVOCATION)
                .containsEntry("skill_name", "search")
                .containsEntry("skill_version", 2)
                .containsEntry("body_chars", 1024);
        assertThat(transcript.appended).containsExactly(message);
    }

    @Test
    void appendPlanModeChangeStoresTypedEventMetadata() {
        InMemoryTranscript transcript = new InMemoryTranscript();
        MessageStore store = new MessageStore(transcript);
        store.bindConversation("c1");

        Message message = store.appendPlanModeChange("OFF", "ACTIVE");

        assertThat(message.role()).isEqualTo(MessageRole.USER);
        assertThat(message.content()).isEqualTo("[plan_mode_change] OFF -> ACTIVE");
        assertThat(message.metadata().values())
                .containsEntry(MessageMetadata.EVENT, MessageMetadata.EVENT_PLAN_MODE_CHANGE)
                .containsEntry("from", "OFF")
                .containsEntry("to", "ACTIVE");
        assertThat(transcript.appended).containsExactly(message);
    }

    private static final class InMemoryTranscript implements TranscriptPort {
        private final List<Message> appended = new ArrayList<>();

        @Override
        public List<Message> append(String conversationId, List<Message> messages) {
            appended.addAll(messages);
            return List.copyOf(messages);
        }

        @Override
        public List<Message> load(String conversationId) {
            return List.copyOf(appended);
        }

        @Override
        public List<Message> replaceForCompaction(
                String conversationId,
                List<Message> messages,
                CompactionTrigger trigger,
                Map<String, Object> metadata) {
            appended.clear();
            appended.addAll(messages);
            return List.copyOf(messages);
        }
    }
}
